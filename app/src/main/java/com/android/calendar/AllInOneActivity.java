/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (C) 2022 The Calyx Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calendar;

import static android.provider.CalendarContract.Attendees.ATTENDEE_STATUS;
import static android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY;
import static android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME;
import static android.provider.CalendarContract.EXTRA_EVENT_END_TIME;

import android.Manifest;
import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Attendees;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.android.calendar.CalendarController.EventHandler;
import com.android.calendar.CalendarController.EventInfo;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.CalendarController.ViewType;
import com.android.calendar.agenda.DatedAgendaFragment;
import com.android.calendar.alerts.AlertService;
import com.android.calendar.month.MonthByWeekFragment;
import com.android.calendar.selectcalendars.SelectVisibleCalendarsFragment;
import com.android.calendar.settings.GeneralPreferences;
import com.android.calendar.settings.SettingsActivity;
import com.android.calendar.settings.SettingsActivityKt;
import com.android.calendar.settings.ViewDetailsPreferences;
import com.android.calendar.theme.DynamicThemeKt;
import com.android.calendar.calendarcommon2.Time;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.datepicker.MaterialPickerOnPositiveButtonClickListener;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

import java.io.File;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import ws.xsoh.etar.R;
import ws.xsoh.etar.databinding.AllInOneMaterialBinding;
import ws.xsoh.etar.databinding.DateRangeTitleBinding;

public class AllInOneActivity extends AbstractCalendarActivity implements EventHandler,
        OnSharedPreferenceChangeListener, SearchView.OnQueryTextListener, SearchView.OnSuggestionListener, NavigationView.OnNavigationItemSelectedListener {
    private static final String TAG = "AllInOneActivity";
    private static final boolean DEBUG = false;
    private static final String BUNDLE_KEY_RESTORE_TIME = "key_restore_time";
    private static final String BUNDLE_KEY_EVENT_ID = "key_event_id";
    private static final String BUNDLE_KEY_RESTORE_VIEW = "key_restore_view";
    private static final String BUNDLE_KEY_AGENDA_STATE = "key_agenda_state";
    private Bundle mAgendaRestoreState;
    private static final int HANDLER_KEY = 0;
    private boolean taskReceiverRegistered;
    private final Handler refreshHandler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshEvents = this::dispatchEventsChanged;
    private final BroadcastReceiver taskChangeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (mController != null) eventsChanged();
        }
    };
    private static final int PERMISSIONS_REQUEST_WRITE_CALENDAR = 0;
    private static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 1;

    // Indices of buttons for the drop down menu (tabs replacement)
    // Must match the strings in the array buttons_list in arrays.xml and the
    // OnNavigationListener
    private static final int BUTTON_DAY_INDEX = 0;
    private static final int BUTTON_WEEK_INDEX = 1;
    private static final int BUTTON_MONTH_INDEX = 2;
    private static final int BUTTON_AGENDA_INDEX = 3;
    private static boolean mIsMultipane;
    private static boolean mIsTabletConfig;
    private static boolean mShowAgendaWithMonth;
    int mOrientation;
    BroadcastReceiver mCalIntentReceiver;
    private CalendarController mController;
    // Create an observer so that we can update the views whenever a
    // Calendar event changes.
    private final ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            eventsChanged();
        }
    };
    private boolean mOnSaveInstanceStateCalled = false;
    private boolean mBackToPreviousView = false;
    private ContentResolver mContentResolver;
    private int mPreviousView;
    private int mCurrentView;
    /** Number of weeks displayed by the month view */
    private static final int MONTH_VIEW_WEEKS = 6;
    /** Month shown by the month view (from its title updates), 0 if unknown */
    private long mMonthViewDisplayedMillis;
    private long mTodayIconRangeStart;
    private long mTodayIconRangeEnd;
    private int mTodayIconRangeView = -1;
    private boolean mPaused = true;
    private boolean mUpdateOnResume = false;
    private boolean mHideControls = false;
    private boolean mShowSideViews = true;
    private boolean mShowWeekNum = false;
    private TextView mHomeTime;
    private TextView mDateRange;
    private TextView mWeekTextView;
    private View mMiniMonth;
    private View mCalendarsList;
    private View mMiniMonthContainer;
    private final AnimatorListener mSlideAnimationDoneListener = new AnimatorListener() {

        @Override
        public void onAnimationCancel(Animator animation) {
        }

        @Override
        public void onAnimationEnd(android.animation.Animator animation) {
            int visibility = mShowSideViews ? View.VISIBLE : View.GONE;
            mMiniMonth.setVisibility(visibility);
            mCalendarsList.setVisibility(visibility);
            mMiniMonthContainer.setVisibility(visibility);
        }

        @Override
        public void onAnimationRepeat(android.animation.Animator animation) {
        }

        @Override
        public void onAnimationStart(android.animation.Animator animation) {
        }
    };
    private FloatingActionButton mFab;
    private View mSecondaryPane;
    private String mTimeZone;
    private boolean mShowCalendarControls;
    private int mWeekNum;
    private int mCalendarControlsAnimationTime;
    private int mControlsAnimateWidth;
    private int mControlsAnimateHeight;
    private long mViewEventId = -1;
    private long mIntentEventStartMillis = -1;
    private long mIntentEventEndMillis = -1;
    private int mIntentAttendeeResponse = Attendees.ATTENDEE_STATUS_NONE;
    private boolean mIntentAllDay = false;
    private Activity mActivity;
    private AllInOneMaterialBinding binding;
    private DrawerLayout mDrawerLayout;
    private Toolbar mToolbar;
    private NavigationView mNavigationView;
    private CalendarToolbarHandler mCalendarToolbarHandler;
    // Action bar
    private ActionBar mActionBar;
    private SearchView mSearchView;
    private MenuItem mSearchMenu;
    private MenuItem mControlsMenu;
    private MenuItem mViewSettings;
    private Menu mOptionsMenu;
    private QueryHandler mHandler;
    private final Runnable mHomeTimeUpdater = new Runnable() {
        @Override
        public void run() {
            mTimeZone = Utils.getTimeZone(AllInOneActivity.this, mHomeTimeUpdater);
            updateSecondaryTitleFields(-1);
            AllInOneActivity.this.invalidateOptionsMenu();
            Utils.setMidnightUpdater(mHandler, mTimeChangesUpdater, mTimeZone);
        }
    };
    // runs every midnight/time changes and refreshes the today icon
    private final Runnable mTimeChangesUpdater = new Runnable() {
        @Override
        public void run() {
            mTimeZone = Utils.getTimeZone(AllInOneActivity.this, mHomeTimeUpdater);
            if (mCurrentView == ViewType.TASKS && mController != null) {
                long now = System.currentTimeMillis();
                mController.setTime(now);
                mTodayIconRangeView = ViewType.TASKS;
                mTodayIconRangeStart = mTodayIconRangeEnd = now;
                if (mCalendarToolbarHandler != null) {
                    mCalendarToolbarHandler.refresh(AllInOneActivity.this);
                    refreshActionbarTitle(now);
                } else if (mToolbar != null) {
                    updateTaskTabletTitle(now);
                }
            }
            if ((mCurrentView == ViewType.REMINDERS || mCurrentView == ViewType.AGENDA) && mController != null) {
                if (mCalendarToolbarHandler != null) mCalendarToolbarHandler.refresh(AllInOneActivity.this);
                else if (mToolbar != null) updateTaskTabletTitle(mController.getTime());
                eventsChanged();
            }
            AllInOneActivity.this.invalidateOptionsMenu();
            Utils.setMidnightUpdater(mHandler, mTimeChangesUpdater, mTimeZone);
        }
    };

    private String mHideString;
    private String mShowString;
    // Params for animating the controls on the right
    private LayoutParams mControlsParams;
    private LinearLayout.LayoutParams mVerticalControlsParams;
    private AllInOneMenuExtensionsInterface mExtensions = ExtensionsFactory
            .getAllInOneMenuExtensions();

    @Override
    protected void onNewIntent(Intent intent) {
        String action = intent.getAction();
        if (DEBUG)
            Log.d(TAG, "New intent received " + intent);
        // Don't change the date if we're just returning to the app's home
        if (Intent.ACTION_VIEW.equals(action)
                && !intent.getBooleanExtra(Utils.INTENT_KEY_HOME, false)) {
            long millis = parseViewAction(intent);
            if (millis == -1) {
                millis = Utils.timeFromIntentInMillis(intent);
            }
            if (millis != -1 && mViewEventId == -1 && mController != null) {
                Time time = new Time(mTimeZone);
                time.set(millis);
                time.normalize();
                mController.sendEvent(this, EventType.GO_TO, time, time, -1, ViewType.CURRENT);
            }
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        DynamicThemeKt.applyTheme(this);
        mActivity = this;
        // This needs to be created before setContentView
        mController = CalendarController.getInstance(this);

        // Check and ask for most needed permissions
        checkAppPermissions();

        // Create notification channels
        AlertService.createChannels(this);

        // Get time from intent or icicle
        long timeMillis = -1;
        int viewType = -1;
        final Intent intent = getIntent();
        if (icicle != null) {
            timeMillis = icicle.getLong(BUNDLE_KEY_RESTORE_TIME);
            viewType = icicle.getInt(BUNDLE_KEY_RESTORE_VIEW, -1);
            mAgendaRestoreState = icicle.getBundle(BUNDLE_KEY_AGENDA_STATE);
        } else {
            String action = intent.getAction();
            if (Intent.ACTION_VIEW.equals(action)) {
                // Open EventInfo later
                timeMillis = parseViewAction(intent);
            }

            if (timeMillis == -1) {
                timeMillis = Utils.timeFromIntentInMillis(intent);
            }
        }

        if (viewType == -1 || viewType > ViewType.MAX_VALUE) {
            viewType = Utils.getViewTypeFromIntentAndSharedPref(this);
        }
        mTimeZone = Utils.getTimeZone(this, mHomeTimeUpdater);
        Time t = new Time(mTimeZone);
        t.set(timeMillis);

        if (DEBUG) {
            if (icicle != null && intent != null) {
                Log.d(TAG, "both, icicle:" + icicle + "  intent:" + intent);
            } else {
                Log.d(TAG, "not both, icicle:" + icicle + " intent:" + intent);
            }
        }

        Resources res = getResources();
        mHideString = res.getString(R.string.hide_controls);
        mShowString = res.getString(R.string.show_controls);
        mOrientation = res.getConfiguration().orientation;
        if (mOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            mControlsAnimateWidth = (int) res.getDimension(R.dimen.calendar_controls_width);
            if (mControlsParams == null) {
                mControlsParams = new LayoutParams(mControlsAnimateWidth, 0);
            }
            mControlsParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        } else {
            // Make sure width is in between allowed min and max width values
            mControlsAnimateWidth = Math.max(res.getDisplayMetrics().widthPixels * 45 / 100,
                    (int) res.getDimension(R.dimen.min_portrait_calendar_controls_width));
            mControlsAnimateWidth = Math.min(mControlsAnimateWidth,
                    (int) res.getDimension(R.dimen.max_portrait_calendar_controls_width));
        }

        mControlsAnimateHeight = (int) res.getDimension(R.dimen.calendar_controls_height);

        mHideControls = !Utils.getSharedPreference(
                this, GeneralPreferences.KEY_SHOW_CONTROLS, true);
        mIsMultipane = Utils.getConfigBool(this, R.bool.multiple_pane_config);
        mIsTabletConfig = Utils.getConfigBool(this, R.bool.tablet_config);
        mShowAgendaWithMonth = Utils.getConfigBool(this, R.bool.show_agenda_with_month);
        mShowCalendarControls =
                Utils.getConfigBool(this, R.bool.show_calendar_controls);
        mCalendarControlsAnimationTime = res.getInteger(R.integer.calendar_controls_animation_time);
        Utils.setAllowWeekForDetailView(mIsMultipane);

        // setContentView must be called before configureActionBar
        binding = AllInOneMaterialBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());


        mDrawerLayout = binding.drawerLayout;
        mNavigationView = binding.navigationView;

        mFab = binding.floatingActionButton;

        if (mIsTabletConfig) {
            mDateRange = binding.include.dateBar;
            mWeekTextView = binding.include.weekNum;
        } else {
            mDateRange = DateRangeTitleBinding.inflate(getLayoutInflater()).getRoot();
        }

        setupToolbar(viewType);
        setupNavDrawer();
        setupFloatingActionButton();

        mHomeTime = binding.include.homeTime;
        mMiniMonth = binding.include.miniMonth;
        if (mIsTabletConfig && mOrientation == Configuration.ORIENTATION_PORTRAIT) {
            mMiniMonth.setLayoutParams(new RelativeLayout.LayoutParams(mControlsAnimateWidth,
                    mControlsAnimateHeight));
        }
        mCalendarsList = binding.include.calendarList;
        mMiniMonthContainer = binding.include.miniMonthContainer;
        mSecondaryPane = binding.include.secondaryPane;

        // Must register as the first activity because this activity can modify
        // the list of event handlers in it's handle method. This affects who
        // the rest of the handlers the controller dispatches to are.
        mController.registerFirstEventHandler(HANDLER_KEY, this);

        initFragments(timeMillis, viewType, icicle);

        // Listen for changes that would require this to be refreshed
        SharedPreferences prefs = GeneralPreferences.Companion.getSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        mContentResolver = getContentResolver();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                    mDrawerLayout.closeDrawers();
                    return;
                }
                if (isTaskView(mCurrentView) && mPreviousView > 0 && mPreviousView != mCurrentView) {
                    mController.sendEvent(this, EventType.GO_TO, null, null, -1, mPreviousView);
                    return;
                }
                if (mCurrentView == ViewType.EDIT || mBackToPreviousView) {
                    mController.sendEvent(this, EventType.GO_TO, null, null, -1, mPreviousView);
                } else {
                    int defaultStartView = Utils.getViewTypeFromIntentAndSharedPref(mActivity);

                    // If the current view is the default one, quit app. If not, go back to default view.
                    if (mCurrentView == defaultStartView
                            || defaultStartView == com.android.calendar.CalendarController.ViewType.AGENDA
                            || defaultStartView == com.android.calendar.CalendarController.ViewType.DAY ) {
                       finish();
                    } else {
                        mController.sendEvent(this, EventType.GO_TO, null, null, -1, defaultStartView);
                    }
                }
            }
        });

    }

    private void checkAppPermissions() {
        // Here, thisActivity is the current activity
        if ((ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_CALENDAR)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED)) {

            ArrayList<String> permissionsList = new ArrayList<>(Arrays.asList(
                    Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CALENDAR)
            );

            // Permission for calendar notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (ContextCompat.checkSelfPermission(this,
                            Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED)) {
                permissionsList.add(Manifest.permission.POST_NOTIFICATIONS);
            }

            // No explanation needed, we can request the permission.
            String[] permissionsArray = new String[permissionsList.size()];
            ActivityCompat.requestPermissions(this,
                    permissionsList.toArray(permissionsArray),
                    PERMISSIONS_REQUEST_WRITE_CALENDAR);
        }

    }

    private void checkAndRequestDisablingDoze() {
        boolean doNotCheckBatteryOptimization = Utils.getSharedPreference(getApplicationContext(), GeneralPreferences.KEY_DO_NOT_CHECK_BATTERY_OPTIMIZATION, false);
        if (!dozeDisabled() && !doNotCheckBatteryOptimization) {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getApplicationContext().getPackageName()));
            startActivity(intent);
        }
    }

    private Boolean dozeDisabled() {
        String packageName = getApplicationContext().getPackageName();
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(packageName);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_WRITE_CALENDAR: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // permission was granted, yay!

                    // Check and ask to disable battery optimizations
                    checkAndRequestDisablingDoze();

                } else {
                    Toast.makeText(getApplicationContext(), R.string.user_rejected_calendar_write_permission, Toast.LENGTH_LONG).show();
                }
                return;
            }

            // other 'case' lines to check for other
            // permissions this app might request
        }

        // Clean up cached ics and vcs files - in case onDestroy() didn't run the last time
        cleanupCachedEventFiles();
    }

    private void setupToolbar(int viewType) {
        mToolbar = binding.toolbar;

        if (!mIsTabletConfig) {
            mCalendarToolbarHandler = new CalendarToolbarHandler(this, mToolbar, viewType);
        } else {
            int titleResource = switch (viewType) {
                case ViewType.REMINDERS -> R.string.reminders_title;
                case ViewType.TASKS -> R.string.tasks_title;
                case ViewType.AGENDA -> R.string.agenda_view;
                case ViewType.DAY -> R.string.day_view;
                case ViewType.MONTH -> R.string.month_view;
                default -> R.string.week_view;
            };
            mToolbar.setTitle(titleResource);
        }
        setSupportActionBar(mToolbar);

        mToolbar.setNavigationOnClickListener(v -> AllInOneActivity.this.openDrawer());
        mToolbar.setOnClickListener(v -> { if (mCurrentView != ViewType.TASKS) goToDate(); });
        mActionBar = getSupportActionBar();
        if (mActionBar == null) return;
        mActionBar.setDisplayHomeAsUpEnabled(true);
        mActionBar.setHomeButtonEnabled(true);
    }

    public void openDrawer() {
        mDrawerLayout.openDrawer(GravityCompat.START);
    }

    public void setupNavDrawer() {
        mNavigationView.setNavigationItemSelectedListener(this);
        showActionBar();
    }

    public void setupFloatingActionButton() {
        mFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                androidx.appcompat.widget.PopupMenu menu =
                        new androidx.appcompat.widget.PopupMenu(AllInOneActivity.this, v);
                menu.getMenu().add(0, 1, 0, R.string.tasks_create_event_type);
                menu.getMenu().add(0, 2, 1, R.string.reminders_title);
                menu.getMenu().add(0, 3, 2, R.string.tasks_title);
                menu.setOnMenuItemClickListener(item -> {
                    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.main_pane);
                    long listId = fragment instanceof com.android.calendar.tasks.UndatedTasksFragment
                            ? ((com.android.calendar.tasks.UndatedTasksFragment) fragment).getListId()
                            : fragment instanceof com.android.calendar.tasks.DatedRemindersFragment
                            ? ((com.android.calendar.tasks.DatedRemindersFragment) fragment).getListId() : 0;
                    if (item.getItemId() == 3) {
                        com.android.calendar.tasks.TaskCalendarUi.createTask(AllInOneActivity.this, listId);
                        return true;
                    }
                    com.android.calendar.tasks.TaskCalendarUi.Selection selected =
                            com.android.calendar.tasks.TaskCalendarUi.selection(mController);
                    long millis = mCurrentView == ViewType.TASKS
                            ? System.currentTimeMillis() : mController.getTime();
                    boolean allDay = false;
                    boolean timed = false;
                    if (selected != null) {
                        millis = selected.time;
                        allDay = selected.allDay;
                        timed = mCurrentView == ViewType.DAY || mCurrentView == ViewType.WEEK;
                    } else {
                        Time t = new Time();
                        t.set(millis);
                        t.setSecond(0);
                        if (t.getMinute() > 30) {
                            t.setHour(t.getHour() + 1);
                            t.setMinute(0);
                        } else if (t.getMinute() > 0 && t.getMinute() < 30) {
                            t.setMinute(30);
                        }
                        millis = t.toMillis();
                    }
                    if (item.getItemId() == 2) {
                        // A reminder without a selected time is an all-day reminder.
                        com.android.calendar.tasks.TaskCalendarUi.createReminder(AllInOneActivity.this,
                                selected == null ? (mCurrentView == ViewType.TASKS
                                        ? System.currentTimeMillis() : mController.getTime()) : millis,
                                timed && !allDay, listId);
                    } else {
                        long calendarId = mCurrentView == ViewType.AGENDA && fragment instanceof DatedAgendaFragment
                                ? ((DatedAgendaFragment) fragment).getSelectedWritableCalendarId() : -1;
                        if (calendarId >= 0) {
                            startActivity(mController.generateCreateEventIntent(millis, -1, allDay, null, calendarId));
                        } else {
                            com.android.calendar.tasks.TaskCalendarUi.create(AllInOneActivity.this,
                                    mController, millis, allDay, timed, false);
                        }
                    }
                    return true;
                });
                menu.show();
            }
        });
    }

    private void showActionBar() {
        if (mActionBar == null) return;
        mActionBar.show();
    }

    private long parseViewAction(final Intent intent) {
        long timeMillis = -1;
        Uri data = intent.getData();
        if (data != null && data.isHierarchical()) {
            List<String> path = data.getPathSegments();
            if (path.size() == 2 && path.get(0).equals("events")) {
                try {
                    mViewEventId = Long.parseLong(data.getLastPathSegment());
                    if (mViewEventId != -1) {
                        mIntentEventStartMillis = intent.getLongExtra(EXTRA_EVENT_BEGIN_TIME, 0);
                        mIntentEventEndMillis = intent.getLongExtra(EXTRA_EVENT_END_TIME, 0);
                        mIntentAttendeeResponse = intent.getIntExtra(
                            ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_NONE);
                        mIntentAllDay = intent.getBooleanExtra(EXTRA_EVENT_ALL_DAY, false);
                        timeMillis = mIntentEventStartMillis;
                    }
                } catch (NumberFormatException e) {
                    // Ignore if mViewEventId can't be parsed
                }
            }
        }
        return timeMillis;
    }

    // Clear buttons used in the agenda view
    private void clearOptionsMenu() {
        if (mOptionsMenu == null) {
            return;
        }
        MenuItem cancelItem = mOptionsMenu.findItem(R.id.action_cancel);
        if (cancelItem != null) {
            cancelItem.setVisible(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!taskReceiverRegistered) {
            ContextCompat.registerReceiver(this, taskChangeReceiver,
                    new android.content.IntentFilter(com.android.calendar.tasks.TaskStore.ACTION_TASKS_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            taskReceiverRegistered = true;
        }

        // Check if the upgrade code has ever been run. If not, force a sync just this one time.
        Utils.trySyncAndDisableUpgradeReceiver(this);

        // Must register as the first activity because this activity can modify
        // the list of event handlers in it's handle method. This affects who
        // the rest of the handlers the controller dispatches to are.
        mController.registerFirstEventHandler(HANDLER_KEY, this);
        mOnSaveInstanceStateCalled = false;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (!Utils.canScheduleAlarms(this)) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                startActivity(intent);
            }
        }

        if (!Utils.isCalendarPermissionGranted(this, true)) {
            //If permission is not granted then just return.
            Log.d(TAG, "Manifest.permission.READ_CALENDAR is not granted");
            return;
        }

        mContentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI,
                true, mObserver);
        if (mUpdateOnResume) {
            initFragments(mController.getTime(), mController.getViewType(), null);
            mUpdateOnResume = false;
        }
        Time t = new Time(mTimeZone);
        t.set(mController.getTime());
        mController.sendEvent(this, EventType.UPDATE_TITLE, t, t, -1, ViewType.CURRENT,
                mController.getDateFlags(), null, null);

        if (mControlsMenu != null) {
            mControlsMenu.setTitle(mHideControls ? mShowString : mHideString);
        }
        mPaused = false;

        if (mViewEventId != -1 && mIntentEventStartMillis != -1 && mIntentEventEndMillis != -1) {
            long currentMillis = System.currentTimeMillis();
            long selectedTime = -1;
            if (currentMillis > mIntentEventStartMillis && currentMillis < mIntentEventEndMillis) {
                selectedTime = currentMillis;
            }
            mController.sendEventRelatedEventWithExtra(this, EventType.VIEW_EVENT, mViewEventId,
                    mIntentEventStartMillis, mIntentEventEndMillis, -1, -1,
                    EventInfo.buildViewExtraLong(mIntentAttendeeResponse, mIntentAllDay),
                    selectedTime);
            mViewEventId = -1;
            mIntentEventStartMillis = -1;
            mIntentEventEndMillis = -1;
            mIntentAllDay = false;
        }
        Utils.setMidnightUpdater(mHandler, mTimeChangesUpdater, mTimeZone);
        // Make sure the today icon is up to date
        invalidateOptionsMenu();

        mCalIntentReceiver = Utils.setTimeChangesReceiver(this, mTimeChangesUpdater);
        // Local tasks are not part of CalendarProvider's observer stream.
        eventsChanged();
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (taskReceiverRegistered) {
            unregisterReceiver(taskChangeReceiver);
            taskReceiverRegistered = false;
        }

        mController.deregisterEventHandler(HANDLER_KEY);
        mPaused = true;
        refreshHandler.removeCallbacks(refreshEvents);
        mHomeTime.removeCallbacks(mHomeTimeUpdater);

        if (!Utils.isCalendarPermissionGranted(this, false)) {
            //If permission is not granted then just return.
            Log.d(TAG, "Manifest.permission.WRITE_CALENDAR is not granted");
            return;
        }

        mContentResolver.unregisterContentObserver(mObserver);
        if (isFinishing()) {
            // Stop listening for changes that would require this to be refreshed
            SharedPreferences prefs = GeneralPreferences.Companion.getSharedPreferences(this);
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        }
        // FRAG_TODO save highlighted days of the week;
        if (mController.getViewType() != ViewType.EDIT && !isTaskView(mController.getViewType())) {
            Utils.setDefaultView(this, mController.getViewType());
        }
        Utils.resetMidnightUpdater(mHandler, mTimeChangesUpdater);
        Utils.clearTimeChangesReceiver(this, mCalIntentReceiver);
    }

    @Override
    protected void onUserLeaveHint() {
        mController.sendEvent(this, EventType.USER_HOME, null, null, -1, ViewType.CURRENT);
        super.onUserLeaveHint();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        mOnSaveInstanceStateCalled = true;
        super.onSaveInstanceState(outState);
        outState.putLong(BUNDLE_KEY_RESTORE_TIME, mController.getTime());
        outState.putInt(BUNDLE_KEY_RESTORE_VIEW, mCurrentView);
        if (mCurrentView == ViewType.EDIT) {
            outState.putLong(BUNDLE_KEY_EVENT_ID, mController.getEventId());
        } else if (mCurrentView == ViewType.AGENDA) {
            FragmentManager fm = getSupportFragmentManager();
            Fragment f = fm.findFragmentById(R.id.main_pane);
            if (f instanceof DatedAgendaFragment) {
                Bundle agendaState = ((DatedAgendaFragment) f).saveViewState();
                outState.putBundle(BUNDLE_KEY_AGENDA_STATE, agendaState);
                outState.putLong(BUNDLE_KEY_RESTORE_TIME, agendaState.getLong("anchor_time", mController.getTime()));
            }
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SharedPreferences prefs = GeneralPreferences.Companion.getSharedPreferences(this);
        prefs.unregisterOnSharedPreferenceChangeListener(this);

        mController.deregisterAllEventHandlers();

        CalendarController.removeInstance(this);

        // Clean up cached ics and vcs files
        cleanupCachedEventFiles();
    }

    /**
     * Cleans up the temporarily generated ics and vcs files in the cache directory
     * The files are of the format *.ics and *.vcs
     */
    private void cleanupCachedEventFiles() {
        File cacheDir = getExternalCacheDir();
        if (cacheDir == null) return;
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String filename = file.getName();
            if (filename.endsWith(".ics") || filename.endsWith(".vcs")) {
                file.delete();
            }
        }
    }



    private void initFragments(long timeMillis, int viewType, Bundle icicle) {
        if (DEBUG) {
            Log.d(TAG, "Initializing to " + timeMillis + " for view " + viewType);
        }
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        if (mShowCalendarControls) {
            Fragment miniMonthFrag = new MonthByWeekFragment(timeMillis, true);
            ft.replace(R.id.mini_month, miniMonthFrag);
            mController.registerEventHandler(R.id.mini_month, (EventHandler) miniMonthFrag);

            Fragment selectCalendarsFrag = new SelectVisibleCalendarsFragment();
            ft.replace(R.id.calendar_list, selectCalendarsFrag);
            mController.registerEventHandler(
                    R.id.calendar_list, (EventHandler) selectCalendarsFrag);
        }
        if (!mShowCalendarControls || viewType == ViewType.EDIT || isTaskView(viewType)) {
            mMiniMonth.setVisibility(View.GONE);
            mCalendarsList.setVisibility(View.GONE);
        }

        EventInfo info = null;
        if (viewType == ViewType.EDIT) {
            mPreviousView = GeneralPreferences.Companion.getSharedPreferences(this).getInt(
                    GeneralPreferences.KEY_START_VIEW, GeneralPreferences.DEFAULT_START_VIEW);

            long eventId = -1;
            Intent intent = getIntent();
            Uri data = intent.getData();
            if (data != null) {
                try {
                    eventId = Long.parseLong(data.getLastPathSegment());
                } catch (NumberFormatException e) {
                    if (DEBUG) {
                        Log.d(TAG, "Create new event");
                    }
                }
            } else if (icicle != null && icicle.containsKey(BUNDLE_KEY_EVENT_ID)) {
                eventId = icicle.getLong(BUNDLE_KEY_EVENT_ID);
            }

            long begin = intent.getLongExtra(EXTRA_EVENT_BEGIN_TIME, -1);
            long end = intent.getLongExtra(EXTRA_EVENT_END_TIME, -1);
            info = new EventInfo();
            if (end != -1) {
                info.endTime = new Time();
                info.endTime.set(end);
            }
            if (begin != -1) {
                info.startTime = new Time();
                info.startTime.set(begin);
            }
            info.id = eventId;
            // We set the viewtype so if the user presses back when they are
            // done editing the controller knows we were in the Edit Event
            // screen. Likewise for eventId
            mController.setViewType(viewType);
            mController.setEventId(eventId);
        } else {
            mPreviousView = viewType;
        }

        setMainPane(ft, R.id.main_pane, viewType, timeMillis, true);
        ft.commit(); // this needs to be after setMainPane()

        Time t = new Time(mTimeZone);
        t.set(timeMillis);
        if (viewType == ViewType.AGENDA && icicle != null) {
            mController.sendEvent(this, EventType.GO_TO, t, null,
                    icicle.getLong(BUNDLE_KEY_EVENT_ID, -1), viewType);
        } else if (viewType != ViewType.EDIT) {
            mController.sendEvent(this, EventType.GO_TO, t, null, -1, viewType);
        }
    }

    protected void updateViewSettingsVisiblility() {
        if (mViewSettings != null) {
            boolean viewSettingsVisible = mController.getViewType() == ViewType.MONTH;
            mViewSettings.setVisible(viewSettingsVisible);
            mViewSettings.setEnabled(viewSettingsVisible);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        mOptionsMenu = menu;
        getMenuInflater().inflate(R.menu.all_in_one_title_bar, menu);
        updateViewSwitchButtons();

        // Add additional options (if any).
        Integer extensionMenuRes = mExtensions.getExtensionMenuResource(menu);
        if (extensionMenuRes != null) {
            getMenuInflater().inflate(extensionMenuRes, menu);
        }

        MenuItem item = menu.findItem(R.id.action_import);
        if (item != null) {
            item.setVisible(true);
        }

        mSearchMenu = menu.findItem(R.id.action_search);
        mSearchView = (SearchView) menu.findItem(R.id.action_search).getActionView();
        if (mSearchView != null) {
            Utils.setUpSearchView(mSearchView, this);
            mSearchView.setOnQueryTextListener(this);
            mSearchView.setOnSuggestionListener(this);
        }

        // Hide the "show/hide controls" button if this is a phone
        // or the view type is "Month" or "Agenda".

        mControlsMenu = menu.findItem(R.id.action_hide_controls);
        if (!mShowCalendarControls) {
            if (mControlsMenu != null) {
                mControlsMenu.setVisible(false);
                mControlsMenu.setEnabled(false);
            }
        } else if (mControlsMenu != null && mController != null
                && (mController.getViewType() == ViewType.MONTH ||
                mController.getViewType() == ViewType.AGENDA || isTaskView(mController.getViewType()))) {
            mControlsMenu.setVisible(false);
            mControlsMenu.setEnabled(false);
        } else if (mControlsMenu != null) {
            mControlsMenu.setTitle(mHideControls ? mShowString : mHideString);
        }

        mViewSettings = menu.findItem(R.id.action_view_settings);
        updateViewSettingsVisiblility();


        MenuItem menuItem = menu.findItem(R.id.action_today);

        // replace the default top layer drawable of the today icon with a
        // custom drawable that shows the day of the month of today
        LayerDrawable icon = (LayerDrawable) menuItem.getIcon();
        Utils.setTodayIcon(icon, this, mTimeZone);
        updateTodayIconTint();
        updateReminderMenuVisibility();

        // Handle warning for disabling battery optimizations
        boolean doNotCheckBatteryOptimization = Utils.getSharedPreference(getApplicationContext(), GeneralPreferences.KEY_DO_NOT_CHECK_BATTERY_OPTIMIZATION, false);
        if (dozeDisabled() || doNotCheckBatteryOptimization) {
            MenuItem menuInfoItem = menu.findItem(R.id.action_info);
            if (menuInfoItem != null) {
                menuInfoItem.setVisible(false);
            }
        }

        return true;
    }

    private static final int REQUEST_CODE_IMPORT = 101;

    /**
     * Shows, in the toolbar, one button for each of the day, week and month views other than
     * the current one, when the "View buttons in the toolbar" setting is enabled
     */
    private static boolean isTaskView(int viewType) {
        return viewType == ViewType.REMINDERS || viewType == ViewType.TASKS;
    }

    private void updateReminderMenuVisibility() {
        if (mOptionsMenu == null) return;
        boolean calendar = !isTaskView(mCurrentView);
        MenuItem goTo = mOptionsMenu.findItem(R.id.action_goto);
        if (goTo != null) goTo.setVisible(mCurrentView != ViewType.TASKS);
        for (int id : new int[]{R.id.action_search, R.id.action_import}) {
            MenuItem item = mOptionsMenu.findItem(id);
            if (item != null) item.setVisible(calendar);
        }
    }

    private void updateViewSwitchButtons() {
        if (mOptionsMenu == null) {
            return;
        }
        boolean enabled = Utils.getSharedPreference(this,
                GeneralPreferences.KEY_VIEW_SWITCH_BUTTONS, false);
        setViewSwitchButton(R.id.action_view_day, enabled && mCurrentView != ViewType.DAY);
        setViewSwitchButton(R.id.action_view_week, enabled && mCurrentView != ViewType.WEEK);
        setViewSwitchButton(R.id.action_view_month, enabled && mCurrentView != ViewType.MONTH);

        // On phones, Android only keeps two "if room" icons next to the overflow menu : force the
        // "Today" icon so that it is not pushed into the overflow menu by the view buttons
        MenuItem today = mOptionsMenu.findItem(R.id.action_today);
        if (today != null) {
            today.setVisible(true);
            today.setShowAsAction(enabled || isTaskView(mCurrentView)
                    ? MenuItem.SHOW_AS_ACTION_ALWAYS : MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
    }

    private void setViewSwitchButton(int itemId, boolean visible) {
        MenuItem item = mOptionsMenu.findItem(itemId);
        if (item != null) {
            item.setVisible(visible);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Time t = null;
        int viewType = ViewType.CURRENT;
        long extras = CalendarController.EXTRA_GOTO_TIME;
        final int itemId = item.getItemId();
        if (itemId == R.id.action_refresh) {
            mController.refreshCalendars();
            return true;
        } else if (itemId == R.id.action_today) {
            t = new Time(mTimeZone);
            t.set(System.currentTimeMillis());
            extras |= CalendarController.EXTRA_GOTO_TODAY;
            mController.sendEvent(this, EventType.GO_TO, t, null, t, -1, viewType, extras, null, null);
            return true;
        } else if (itemId == R.id.action_view_day) {
            mController.sendEvent(this, EventType.GO_TO, null, null, -1, ViewType.DAY);
            return true;
        } else if (itemId == R.id.action_view_week) {
            mController.sendEvent(this, EventType.GO_TO, null, null, -1, ViewType.WEEK);
            return true;
        } else if (itemId == R.id.action_view_month) {
            mController.sendEvent(this, EventType.GO_TO, null, null, -1, ViewType.MONTH);
            return true;
        } else if (itemId == R.id.action_goto) {
            goToDate();
        } else if (itemId == R.id.action_hide_controls) {
            mHideControls = !mHideControls;
            Utils.setSharedPreference(
                    this, GeneralPreferences.KEY_SHOW_CONTROLS, !mHideControls);
            item.setTitle(mHideControls ? mShowString : mHideString);
            if (!mHideControls) {
                mMiniMonth.setVisibility(View.VISIBLE);
                mCalendarsList.setVisibility(View.VISIBLE);
                mMiniMonthContainer.setVisibility(View.VISIBLE);
            }
            final ObjectAnimator slideAnimation = ObjectAnimator.ofInt(this, "controlsOffset",
                    mHideControls ? 0 : mControlsAnimateWidth,
                    mHideControls ? mControlsAnimateWidth : 0);
            slideAnimation.setDuration(mCalendarControlsAnimationTime);
            ObjectAnimator.setFrameDelay(0);
            slideAnimation.start();
            return true;
        } else if (itemId == R.id.action_search) {
            return false;
        } else if (itemId == R.id.action_import) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimetypes = {"text/calendar", "text/x-vcalendar", "application/ics"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
            startActivityForResult(intent, REQUEST_CODE_IMPORT);
        } else if (itemId == R.id.action_view_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.putExtra(SettingsActivityKt.EXTRA_SHOW_FRAGMENT, ViewDetailsPreferences.class.getName());
            startActivity(intent);
        } else if (itemId == R.id.action_info) {
            checkAndRequestDisablingDoze();
        } else {
                return mExtensions.handleItemSelected(item, this);
        }

        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_IMPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                Intent importIntent = new Intent(this, ImportActivity.class);
                importIntent.setData(uri);
                startActivity(importIntent);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.day_menu_item) {
            if (mCurrentView != ViewType.DAY) {
                switchToView(ViewType.DAY);
            }
        } else if (itemId == R.id.week_menu_item) {
            if (mCurrentView != ViewType.WEEK) {
                switchToView(ViewType.WEEK);
            }
        } else if (itemId == R.id.month_menu_item) {
            if (mCurrentView != ViewType.MONTH) {
                mController.sendEvent(this, EventType.GO_TO, null, null, -1, ViewType.MONTH);
            }
        } else if (itemId == R.id.agenda_menu_item) {
            if (mCurrentView != ViewType.AGENDA) {
                switchToView(ViewType.AGENDA);
            }
        } else if (itemId == R.id.reminders_menu_item) {
            if (mCurrentView != ViewType.REMINDERS) {
                Time today = new Time(mTimeZone);
                today.set(System.currentTimeMillis());
                mController.sendEvent(this, EventType.GO_TO, today, today, -1, ViewType.REMINDERS);
            }
        } else if (itemId == R.id.tasks_menu_item) {
            if (mCurrentView != ViewType.TASKS) {
                Time today = new Time(mTimeZone);
                today.set(System.currentTimeMillis());
                mController.sendEvent(this, EventType.GO_TO, today, today, -1, ViewType.TASKS);
            }
        } else if (itemId == R.id.action_settings) {
            mController.sendEvent(this, EventType.LAUNCH_SETTINGS, null, null, 0, 0);
        } else if (itemId == R.id.action_about) {
            Intent intent = new Intent(this, AboutActivity.class);
            startActivity(intent);
        }
        mDrawerLayout.closeDrawers();
        return true;
    }

    /**
     * Sets the offset of the controls on the right for animating them off/on
     * screen. ProGuard strips this if it's not in proguard.flags
     *
     * @param controlsOffset The current offset in pixels
     */
    public void setControlsOffset(int controlsOffset) {
        if (mOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            mMiniMonth.setTranslationX(controlsOffset);
            mCalendarsList.setTranslationX(controlsOffset);
            mControlsParams.width = Math.max(0, mControlsAnimateWidth - controlsOffset);
            mMiniMonthContainer.setLayoutParams(mControlsParams);
        } else {
            mMiniMonth.setTranslationY(controlsOffset);
            mCalendarsList.setTranslationY(controlsOffset);
            if (mVerticalControlsParams == null) {
                mVerticalControlsParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, mControlsAnimateHeight);
            }
            mVerticalControlsParams.height = Math.max(0, mControlsAnimateHeight - controlsOffset);
            mMiniMonthContainer.setLayoutParams(mVerticalControlsParams);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key.equals(GeneralPreferences.KEY_WEEK_START_DAY) || key.equals(GeneralPreferences.KEY_DAYS_PER_WEEK)) {
            if (mPaused) {
                mUpdateOnResume = true;
            } else {
                initFragments(mController.getTime(), mController.getViewType(), null);
            }
        }
    }

    private void setMainPane(
            FragmentTransaction ft, int viewId, int viewType, long timeMillis, boolean force) {
        if (mOnSaveInstanceStateCalled) {
            return;
        }
        if (!force && mCurrentView == viewType) {
            return;
        }

        // Remove this when transition to and from month view looks fine.
        boolean doTransition = viewType != ViewType.MONTH && mCurrentView != ViewType.MONTH;
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (viewType != mCurrentView) {
            // The rules for this previous view are different than the
            // controller's and are used for intercepting the back button.
            if (mCurrentView != ViewType.EDIT && mCurrentView > 0) {
                mPreviousView = mCurrentView;
            }
            mCurrentView = viewType;
            updateViewSwitchButtons();
        }
        updateReminderMenuVisibility();
        // Navigation has two groups; keep a single calendar view checked even
        // when navigation originates from a gesture or a toolbar button.
        mNavigationView.getMenu().findItem(R.id.day_menu_item).setChecked(false);
        mNavigationView.getMenu().findItem(R.id.week_menu_item).setChecked(false);
        mNavigationView.getMenu().findItem(R.id.month_menu_item).setChecked(false);
        mNavigationView.getMenu().findItem(R.id.agenda_menu_item).setChecked(false);
        mNavigationView.getMenu().findItem(R.id.reminders_menu_item).setChecked(false);
        mNavigationView.getMenu().findItem(R.id.tasks_menu_item).setChecked(false);
        // Create new fragment
        Fragment frag = null;
        Fragment secFrag = null;
        switch (viewType) {
            case ViewType.REMINDERS:
                mNavigationView.getMenu().findItem(R.id.reminders_menu_item).setChecked(true);
                frag = new com.android.calendar.tasks.DatedRemindersFragment();
                break;
            case ViewType.TASKS:
                mNavigationView.getMenu().findItem(R.id.tasks_menu_item).setChecked(true);
                frag = new com.android.calendar.tasks.UndatedTasksFragment();
                if (mIsTabletConfig) {
                    mToolbar.setTitle(R.string.tasks_title);
                    mToolbar.setSubtitle(null);
                }
                break;
            case ViewType.AGENDA:
                mNavigationView.getMenu().findItem(R.id.agenda_menu_item).setChecked(true);
                frag = DatedAgendaFragment.newInstance(timeMillis, mAgendaRestoreState);
                mAgendaRestoreState = null;
                if (mIsTabletConfig) {
                    mToolbar.setTitle(R.string.agenda_view);
                }
                break;
            case ViewType.DAY:
                mNavigationView.getMenu().findItem(R.id.day_menu_item).setChecked(true);
                frag = new DayFragment(timeMillis, 1);
                if (mIsTabletConfig) {
                    mToolbar.setTitle(R.string.day_view);
                }
                break;
            case ViewType.MONTH:
                mNavigationView.getMenu().findItem(R.id.month_menu_item).setChecked(true);
                frag = new MonthByWeekFragment(timeMillis, false);
                if (mShowAgendaWithMonth) {
                    secFrag = DatedAgendaFragment.newInstance(timeMillis, null);
                }
                if (mIsTabletConfig) {
                    mToolbar.setTitle(R.string.month_view);
                }
                break;
            case ViewType.WEEK:
            default:
                mNavigationView.getMenu().findItem(R.id.week_menu_item).setChecked(true);
                frag = new DayFragment(timeMillis, Utils.getDaysPerWeek(this));
                if (mIsTabletConfig) {
                    mToolbar.setTitle(R.string.week_view);
                }
                break;
        }
        // Update the current view so that the menu can update its look according to the
        // current view.
        if (mCalendarToolbarHandler != null) {
            mCalendarToolbarHandler.setCurrentMainView(viewType);
        }

        if (!mIsTabletConfig) {
            refreshActionbarTitle(timeMillis);
        } else if (isTaskView(viewType)) {
            updateTaskTabletTitle(timeMillis);
        }



        // Show date only on tablet configurations in views different than Agenda
        if (!mIsTabletConfig) {
            mDateRange.setVisibility(View.GONE);
        } else if (viewType != ViewType.AGENDA && !isTaskView(viewType)) {
            mDateRange.setVisibility(View.VISIBLE);
        } else {
            mDateRange.setVisibility(View.GONE);
        }

        // Clear unnecessary buttons from the option menu when switching from the agenda view
        if (viewType != ViewType.AGENDA) {
            clearOptionsMenu();
        }

        boolean doCommit = false;
        if (ft == null) {
            doCommit = true;
            ft = fragmentManager.beginTransaction();
        }

        if (doTransition) {
            ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        }

        ft.replace(viewId, frag);
        if (mShowAgendaWithMonth) {

            // Show/hide secondary fragment

            if (secFrag != null) {
                ft.replace(R.id.secondary_pane, secFrag);
                mSecondaryPane.setVisibility(View.VISIBLE);
            } else {
                mSecondaryPane.setVisibility(View.GONE);
                Fragment f = fragmentManager.findFragmentById(R.id.secondary_pane);
                if (f != null) {
                    ft.remove(f);
                }
                mController.deregisterEventHandler(R.id.secondary_pane);
            }
        }
        if (DEBUG) {
            Log.d(TAG, "Adding handler with viewId " + viewId + " and type " + viewType);
        }
        // If the key is already registered this will replace it
        mController.registerEventHandler(viewId, (EventHandler) frag);
        if (secFrag != null) {
            mController.registerEventHandler(R.id.secondary_pane, (EventHandler) secFrag);
        }

        if (doCommit) {
            if (DEBUG) {
                Log.d(TAG, "setMainPane AllInOne=" + this + " finishing:" + this.isFinishing());
            }
            ft.commit();
        }
    }

    private void refreshActionbarTitle(long timeMillis) {
        if (mCalendarToolbarHandler != null) {
            mCalendarToolbarHandler.setTime(timeMillis);
        }
    }

    private void updateTaskTabletTitle(long millis) {
        String date = Utils.formatDateRange(this, millis, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR);
        mToolbar.setTitle(CalendarToolbarHandler.appendShortWeekday(this, date, millis, mTimeZone));
        ZoneId zone = getZoneId();
        LocalDate day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        mToolbar.setSubtitle(LocalizationSettings.lunar(this)
                ? LunarUtils.header(this, day, day, day, LocalDate.now(zone), false) : null);
    }

    private void setTitleInActionBar(EventInfo event) {
        if (event.eventType != EventType.UPDATE_TITLE) return;
        if (isTaskView(mCurrentView)) {
            if (mIsTabletConfig) updateTaskTabletTitle(mController.getTime());
            return;
        }

        final long start = event.startTime.toMillis();
        final long end;
        if (event.endTime != null) {
            end = event.endTime.toMillis();
        } else {
            end = start;
        }

        final String msg = Utils.formatDateRange(this, start, end, (int) event.extraLong);
        CharSequence dateTitle = mIsTabletConfig && mCurrentView == ViewType.DAY
                ? CalendarToolbarHandler.appendShortWeekday(this, msg, start, mTimeZone) : msg;
        CharSequence oldDate = mDateRange.getText();
        if (mIsTabletConfig && LocalizationSettings.lunar(this)) {
            ZoneId zone = getZoneId();
            LocalDate first = Instant.ofEpochMilli(start).atZone(zone).toLocalDate();
            LocalDate last = Instant.ofEpochMilli(end).atZone(zone).toLocalDate();
            LocalDate reference = event.selectedTime != null
                    ? Instant.ofEpochMilli(event.selectedTime.toMillis()).atZone(zone).toLocalDate() : first;
            boolean monthOnly = mCurrentView == ViewType.MONTH || mCurrentView == ViewType.WEEK;
            if (mCurrentView == ViewType.MONTH) {
                first = first.withDayOfMonth(1);
                last = first.withDayOfMonth(first.lengthOfMonth());
            }
            String lunar = LunarUtils.header(this, reference, first, last, LocalDate.now(zone), monthOnly);
            if (!lunar.isEmpty()) {
                android.text.SpannableStringBuilder label = new android.text.SpannableStringBuilder(dateTitle);
                label.append("\n").append(lunar);
                label.setSpan(new android.text.style.RelativeSizeSpan(0.65f), dateTitle.length() + 1,
                        label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                mDateRange.setText(label);
            } else mDateRange.setText(dateTitle);
        } else mDateRange.setText(dateTitle);
        updateSecondaryTitleFields(event.selectedTime != null ? event.selectedTime.toMillis()
                : start);
        if (!TextUtils.equals(oldDate, mDateRange.getText())) {
            mDateRange.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
            if (mShowWeekNum && mWeekTextView != null) {
                mWeekTextView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
            }
        }
    }

    private void updateSecondaryTitleFields(long visibleMillisSinceEpoch) {
        mShowWeekNum = Utils.getShowWeekNumber(this);
        mTimeZone = Utils.getTimeZone(this, mHomeTimeUpdater);
        if (visibleMillisSinceEpoch != -1) {
            mWeekNum = Utils.getWeekNumberFromTime(visibleMillisSinceEpoch, this);
        }

        if (mShowWeekNum && (mCurrentView == ViewType.WEEK) && mIsTabletConfig
                && mWeekTextView != null) {
            String weekString = getResources().getQuantityString(R.plurals.weekN, mWeekNum,
                    mWeekNum);
            mWeekTextView.setText(weekString);
            mWeekTextView.setVisibility(View.VISIBLE);
        } else if (mWeekTextView != null) {
            mWeekTextView.setVisibility(View.GONE);
        }

        if (mHomeTime != null
                && (mCurrentView == ViewType.DAY || mCurrentView == ViewType.WEEK
                        || mCurrentView == ViewType.AGENDA)
                && !TextUtils.equals(mTimeZone, Utils.getCurrentTimezone())) {
            Time time = new Time(mTimeZone);
            time.set(System.currentTimeMillis());
            long millis = time.toMillis();
            int flags = DateUtils.FORMAT_SHOW_TIME;
            if (DateFormat.is24HourFormat(this)) {
                flags |= DateUtils.FORMAT_24HOUR;
            }
            // Formats the time as
            String timeString = (new StringBuilder(
                    Utils.formatDateRange(this, millis, millis, flags))).append(" ").append(
                    TimeZone.getTimeZone(mTimeZone).getDisplayName(
                            false, TimeZone.SHORT, Locale.getDefault())).toString();
            mHomeTime.setText(timeString);
            mHomeTime.setVisibility(View.VISIBLE);
            // Update when the minute changes
            mHomeTime.removeCallbacks(mHomeTimeUpdater);
            mHomeTime.postDelayed(
                    mHomeTimeUpdater,
                    DateUtils.MINUTE_IN_MILLIS - (millis % DateUtils.MINUTE_IN_MILLIS));
        } else if (mHomeTime != null) {
            mHomeTime.setVisibility(View.GONE);
        }
    }

    public void goToDate() {
        MaterialPickerOnPositiveButtonClickListener<Long> materialPickerOnPositiveButtonClickListener = new MaterialPickerOnPositiveButtonClickListener<>() {
            @Override
            public void onPositiveButtonClick(Long selection) {
                Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                calendar.setTime(new Date(selection));

                Time selectedTime = new Time(mTimeZone);
                selectedTime.set(System.currentTimeMillis());  // Needed for recalc function in DayView(time + gmtoff)
                selectedTime.setYear(calendar.get(Calendar.YEAR));
                selectedTime.setMonth(calendar.get(Calendar.MONTH));
                selectedTime.setDay(calendar.get(Calendar.DAY_OF_MONTH));

                long extras = CalendarController.EXTRA_GOTO_TIME | CalendarController.EXTRA_GOTO_DATE;
                mController.sendEvent(this, EventType.GO_TO, selectedTime, null, selectedTime, -1, ViewType.CURRENT, extras, null, null);
            }
        };

        long selection = java.time.LocalDate.now(java.time.ZoneId.of(mTimeZone))
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        MaterialDatePicker<Long> datePickerDialog = ItemDateTimePickers.date(this, selection);

        datePickerDialog.addOnPositiveButtonClickListener(materialPickerOnPositiveButtonClickListener);
        datePickerDialog.show(getSupportFragmentManager(), "GoTo");
    }

    @Override
    public long getSupportedEventTypes() {
        return EventType.GO_TO | EventType.UPDATE_TITLE;
    }

    @Override
    public void handleEvent(EventInfo event) {
        long displayTime = -1;
        if (event.eventType == EventType.GO_TO) {
            if ((event.extraLong & CalendarController.EXTRA_GOTO_BACK_TO_PREVIOUS) != 0) {
                mBackToPreviousView = true;
            } else if (event.viewType != mController.getPreviousViewType()
                    && event.viewType != ViewType.EDIT) {
                // Clear the flag is change to a different view type
                mBackToPreviousView = false;
            }

            // Check toMillis method for the value -1 and if yes add one hour.
            // This prevents the date "1970" from being displayed on the day of the daylight saving time changeover when you tap on the hour that is skipped.
            if (event.startTime.toMillis() == -1) {
                event.startTime.set(0, 0, 1, event.startTime.getDay(), event.startTime.getMonth(), event.startTime.getYear());
            }

            setMainPane(
                    null, R.id.main_pane, event.viewType, event.startTime.toMillis(), false);
            if (mSearchView != null) {
                mSearchView.clearFocus();
            }
            if (mShowCalendarControls) {
                int animationSize = (mOrientation == Configuration.ORIENTATION_LANDSCAPE) ?
                        mControlsAnimateWidth : mControlsAnimateHeight;
                boolean noControlsView = event.viewType == ViewType.MONTH || event.viewType == ViewType.AGENDA
                        || isTaskView(event.viewType);
                if (mControlsMenu != null) {
                    mControlsMenu.setVisible(!noControlsView);
                    mControlsMenu.setEnabled(!noControlsView);
                }
                if (noControlsView || mHideControls) {
                    // hide minimonth and calendar frag
                    mShowSideViews = false;
                    if (!mHideControls) {
                            final ObjectAnimator slideAnimation = ObjectAnimator.ofInt(this,
                                    "controlsOffset", 0, animationSize);
                            slideAnimation.addListener(mSlideAnimationDoneListener);
                            slideAnimation.setDuration(mCalendarControlsAnimationTime);
                            ObjectAnimator.setFrameDelay(0);
                            slideAnimation.start();
                    } else {
                        mMiniMonth.setVisibility(View.GONE);
                        mCalendarsList.setVisibility(View.GONE);
                        mMiniMonthContainer.setVisibility(View.GONE);
                    }
                } else {
                    // show minimonth and calendar frag
                    mShowSideViews = true;
                    mMiniMonth.setVisibility(View.VISIBLE);
                    mCalendarsList.setVisibility(View.VISIBLE);
                    mMiniMonthContainer.setVisibility(View.VISIBLE);
                    if (!mHideControls &&
                            (mController.getPreviousViewType() == ViewType.MONTH ||
                             mController.getPreviousViewType() == ViewType.AGENDA ||
                             isTaskView(mController.getPreviousViewType()))) {
                        final ObjectAnimator slideAnimation = ObjectAnimator.ofInt(this,
                                "controlsOffset", animationSize, 0);
                        slideAnimation.setDuration(mCalendarControlsAnimationTime);
                        ObjectAnimator.setFrameDelay(0);
                        slideAnimation.start();
                    }
                }
            }
            updateViewSettingsVisiblility();
            displayTime = event.selectedTime != null ? event.selectedTime.toMillis()
                    : event.startTime.toMillis();
            if (!mIsTabletConfig) {
                refreshActionbarTitle(displayTime);
            } else if (isTaskView(mCurrentView)) {
                updateTaskTabletTitle(displayTime);
            }
            if (isTaskView(mCurrentView)) {
                mTodayIconRangeView = mCurrentView;
                mTodayIconRangeStart = mTodayIconRangeEnd = displayTime;
                updateTodayIconTint();
            }
        } else if (event.eventType == EventType.UPDATE_TITLE) {
            if (mCurrentView == ViewType.MONTH && event.startTime != null) {
                mMonthViewDisplayedMillis = event.startTime.toMillis();
            }
            if (event.startTime != null) {
                mTodayIconRangeStart = event.startTime.toMillis();
                mTodayIconRangeEnd = event.endTime != null
                        ? event.endTime.toMillis() : mTodayIconRangeStart;
                mTodayIconRangeView = mCurrentView;
            }
            updateTodayIconTint();
            setTitleInActionBar(event);
            if (mCalendarToolbarHandler != null) {
                long titleTime = mCurrentView == ViewType.MONTH && event.startTime != null
                        ? event.startTime.toMillis() : mController.getTime();
                long start = event.startTime != null ? event.startTime.toMillis() : titleTime;
                long end = event.endTime != null ? event.endTime.toMillis() : start;
                mCalendarToolbarHandler.setTime(titleTime, start, end);
            }
        }
        updateSecondaryTitleFields(displayTime);
    }

    // Needs to be in proguard whitelist
    // Specified as listener via android:onClick in a layout xml
    public void handleSelectSyncedCalendarsClicked(View v) {
        mController.sendEvent(this, EventType.LAUNCH_SETTINGS, null, null, null, 0, 0,
                CalendarController.EXTRA_GOTO_TIME, null,
                null);
    }

    /**
     * Switches to another view. Leaving the month view, the current week (today) is shown when
     * it is visible in the month, rather than the first week displayed on screen.
     */
    private void switchToView(int viewType) {
        Time start = null;
        if (mCurrentView == ViewType.MONTH && viewType != ViewType.MONTH) {
            start = todayIfVisibleInMonthView();
        }
        mController.sendEvent(this, EventType.GO_TO, start, null, -1, viewType);
    }

    /**
     * Today, if it is part of the weeks displayed by the month view ; `null` otherwise.
     * The date arithmetic is done with `java.time` ; only the result is converted to a `Time`.
     */
    private Time todayIfVisibleInMonthView() {
        if (mMonthViewDisplayedMillis <= 0) {
            return null;
        }
        ZoneId zone = getZoneId();
        LocalDate today = LocalDate.now(zone);

        // The month view starts with the week containing the 1st of the displayed month
        LocalDate firstOfMonth = Instant.ofEpochMilli(mMonthViewDisplayedMillis)
                .atZone(zone).toLocalDate().withDayOfMonth(1);
        LocalDate firstVisibleDay = firstOfMonth.with(
                TemporalAdjusters.previousOrSame(getFirstDayOfWeek()));
        LocalDate lastVisibleDay = firstVisibleDay.plusWeeks(MONTH_VIEW_WEEKS).minusDays(1);

        if (today.isBefore(firstVisibleDay) || today.isAfter(lastVisibleDay)) {
            return null;
        }
        Time now = new Time(mTimeZone);
        now.setToNow();
        return now;
    }

    private void updateTodayIconTint() {
        if (mOptionsMenu == null || mController == null) return;
        MenuItem item = mOptionsMenu.findItem(R.id.action_today);
        if (item == null || item.getIcon() == null) return;
        ZoneId zone = getZoneId();
        LocalDate today = LocalDate.now(zone);
        long reference = mTodayIconRangeView == mCurrentView
                ? mTodayIconRangeStart : mController.getTime();
        LocalDate first = Instant.ofEpochMilli(reference).atZone(zone).toLocalDate();
        LocalDate last = first;
        if (mCurrentView == ViewType.MONTH) {
            first = first.withDayOfMonth(1).with(
                    TemporalAdjusters.previousOrSame(getFirstDayOfWeek()));
            last = first.plusWeeks(MONTH_VIEW_WEEKS).minusDays(1);
        } else if (mCurrentView == ViewType.WEEK) {
            first = first.with(TemporalAdjusters.previousOrSame(getFirstDayOfWeek()));
            last = first.plusDays(6);
        } else if ((mCurrentView == ViewType.AGENDA || mCurrentView == ViewType.REMINDERS) && mTodayIconRangeView == mCurrentView) {
            last = Instant.ofEpochMilli(mTodayIconRangeEnd).atZone(zone).toLocalDate();
        }
        boolean visible = !today.isBefore(first) && !today.isAfter(last);
        if (!(item.getIcon() instanceof LayerDrawable)) return;
        LayerDrawable icon = (LayerDrawable) item.getIcon().mutate();
        int normal = com.google.android.material.color.MaterialColors.getColor(
                this, android.R.attr.colorControlNormal, android.graphics.Color.BLACK);
        int color = visible ? normal : com.google.android.material.color.MaterialColors.getColor(
                this, androidx.appcompat.R.attr.colorPrimary, normal);
        android.graphics.drawable.Drawable frame = icon.findDrawableByLayerId(R.id.today_icon_background);
        if (frame != null) {
            frame.setTint(visible
                    ? DynamicThemeKt.getColor(this, "month_today_bgcolor")
                    : color);
        }
        android.graphics.drawable.Drawable day = icon.findDrawableByLayerId(R.id.today_icon_day);
        if (day instanceof DayOfMonthDrawable) {
            ((DayOfMonthDrawable) day).setTextColor(color);
        }
        item.setIcon(icon);
    }

    /** Timezone of the calendar views, falling back to the device one */
    private ZoneId getZoneId() {
        try {
            if (mTimeZone != null) {
                return ZoneId.of(mTimeZone);
            }
        } catch (DateTimeException e) {
            Log.w(TAG, "Unknown time zone " + mTimeZone + ", using the device one", e);
        }
        return ZoneId.systemDefault();
    }

    /** First day of the week setting, as a DayOfWeek (Utils returns Time.SUNDAY = 0, SATURDAY = 6) */
    private DayOfWeek getFirstDayOfWeek() {
        int timeDay = Utils.getFirstDayOfWeek(this);
        return timeDay == Time.SUNDAY ? DayOfWeek.SUNDAY : DayOfWeek.of(timeDay);
    }

    @Override
    public void eventsChanged() {
        refreshHandler.removeCallbacks(refreshEvents);
        refreshHandler.postDelayed(refreshEvents, 32);
    }

    private void dispatchEventsChanged() {
        if (!mPaused && mController != null)
            mController.sendEvent(this, EventType.EVENTS_CHANGED, null, null, -1, ViewType.CURRENT);
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        return false;
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        mSearchMenu.collapseActionView();
        mController.sendEvent(this, EventType.SEARCH, null, null, -1, ViewType.CURRENT, 0, query,
                getComponentName());
        return true;
    }

    @Override
    public boolean onSuggestionSelect(int position) {
        return false;
    }

    @Override
    public boolean onSuggestionClick(int position) {
        mSearchMenu.collapseActionView();
        return false;
    }

    @Override
    public boolean onSearchRequested() {
        if (mSearchMenu != null) {
            mSearchMenu.expandActionView();
        }
        return false;
    }

    private class QueryHandler extends AsyncQueryHandler {
        public QueryHandler(ContentResolver cr) {
            super(cr);
        }
    }
}
