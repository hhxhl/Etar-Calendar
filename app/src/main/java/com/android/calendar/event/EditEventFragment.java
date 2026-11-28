/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.calendar.event;

import static com.android.calendar.event.EditEventHelper.EXTENDED_INDEX_NAME;
import static com.android.calendar.event.EditEventHelper.EXTENDED_INDEX_VALUE;

import android.Manifest;
import android.app.Activity;
import android.content.AsyncQueryHandler;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Colors;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.android.calendar.AsyncQueryService;
import com.android.calendar.CalendarController;
import com.android.calendar.CalendarController.EventHandler;
import com.android.calendar.CalendarController.EventInfo;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.CalendarEventModel;
import com.android.calendar.CalendarEventModel.Attendee;
import com.android.calendar.CalendarEventModel.ReminderEntry;
import com.android.calendar.DeleteEventHelper;
import com.android.calendar.Utils;
import com.android.calendar.calendarcommon2.Time;
import com.android.calendar.colorpicker.ColorPickerSwatch.OnColorSelectedListener;
import com.android.calendar.colorpicker.HsvColorComparator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;

import ws.xsoh.etar.R;

public class EditEventFragment extends Fragment implements EventHandler, OnColorSelectedListener {
    private static final String TAG = "EditEventActivity";
    private static final String COLOR_PICKER_DIALOG_TAG = "ColorPickerDialog";

    private static final int REQUEST_CODE_COLOR_PICKER = 0;

    private static final String BUNDLE_KEY_MODEL = "key_model";
    private static final String BUNDLE_KEY_EDIT_STATE = "key_edit_state";
    private static final String BUNDLE_KEY_EVENT = "key_event";
    private static final String BUNDLE_KEY_READ_ONLY = "key_read_only";
    private static final String BUNDLE_KEY_EDIT_ON_LAUNCH = "key_edit_on_launch";
    private static final String BUNDLE_KEY_SHOW_COLOR_PALETTE = "show_color_palette";

    private static final String BUNDLE_KEY_DATE_BUTTON_CLICKED = "date_button_clicked";

    private static final boolean DEBUG = false;

    private static final int TOKEN_EVENT = 1;
    private static final int TOKEN_ATTENDEES = 1 << 1;
    private static final int TOKEN_REMINDERS = 1 << 2;
    private static final int TOKEN_CALENDARS = 1 << 3;
    private static final int TOKEN_COLORS = 1 << 4;
    private static final int TOKEN_EXTENDED = 1 << 5;

    private static final int TOKEN_ALL = TOKEN_EVENT | TOKEN_ATTENDEES | TOKEN_REMINDERS
            | TOKEN_CALENDARS | TOKEN_COLORS | TOKEN_EXTENDED;
    private static final int TOKEN_UNITIALIZED = 1 << 31;
    private final EventInfo mEvent;
    private final Done mOnDone = new Done();
    private final Intent mIntent;
    public boolean mShowModifyDialogOnLaunch = false;
    EditEventHelper mHelper;
    CalendarEventModel mModel;
    CalendarEventModel mOriginalModel;
    CalendarEventModel mRestoreModel;
    EditEventView mView;
    QueryHandler mHandler;
    int mModification = Utils.MODIFY_UNINITIALIZED;
    /**
     * A bitfield of TOKEN_* to keep track which query hasn't been completed
     * yet. Once all queries have returned, the model can be applied to the
     * view.
     */
    private int mOutstandingQueries = TOKEN_UNITIALIZED;
    private AlertDialog mModifyDialog;
    private EventBundle mEventBundle;
    private ArrayList<ReminderEntry> mReminders;
    private int mEventColor;
    private boolean mEventColorInitialized = false;
    private Uri mUri;
    private long mBegin;
    private long mEnd;
    private long mCalendarId = -1;
    private AppCompatActivity mActivity;
    private boolean mSaveOnDetach = true;
    private boolean mIsReadOnly = false;
    private boolean mShowColorPalette = false;
    private InputMethodManager mInputMethodManager;
    private final View.OnClickListener mActionBarListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            onActionBarItemSelected(v.getId());
        }
    };
    private boolean mUseCustomActionBar;
    private boolean mExistingEvent;
    private String mExportText;
    private final androidx.activity.result.ActivityResultLauncher<Intent> mExportLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                                || result.getData().getData() == null || mExportText == null) return;
                        try (java.io.OutputStream output = requireContext().getContentResolver()
                                .openOutputStream(result.getData().getData())) {
                            if (output == null) throw new java.io.IOException("No output stream");
                            output.write(mExportText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            Toast.makeText(requireContext(), R.string.cal_export_success, Toast.LENGTH_SHORT).show();
                        } catch (java.io.IOException | SecurityException error) {
                            Toast.makeText(requireContext(), R.string.cal_export_error, Toast.LENGTH_SHORT).show();
                        } finally {
                            mExportText = null;
                        }
                    });
    private View.OnClickListener mOnColorPickerClicked = new View.OnClickListener() {

        @Override
        public void onClick(View v) {
            FragmentManager manager = getParentFragmentManager();
            if (manager.isStateSaved() || manager.findFragmentByTag(COLOR_PICKER_DIALOG_TAG) != null) return;
            EventColorPickerDialog picker = EventColorPickerDialog.newInstance(mModel.getCalendarEventColors(),
                    mModel.getEventColor(), mModel.getCalendarColor(), mView.mIsMultipane);
            picker.setOnColorSelectedListener(EditEventFragment.this);
            picker.showOnce(manager, COLOR_PICKER_DIALOG_TAG);
        }
    };

    public EditEventFragment() {
        this(null, null, false, -1, false, null);
    }

    public EditEventFragment(EventInfo event, ArrayList<ReminderEntry> reminders,
                             boolean eventColorInitialized, int eventColor, boolean readOnly, Intent intent) {
        mEvent = event;
        mIsReadOnly = readOnly;
        mIntent = intent;

        mReminders = reminders;
        mEventColorInitialized = eventColorInitialized;
        if (eventColorInitialized) {
            mEventColor = eventColor;
        }
        setHasOptionsMenu(true);
    }

    private void setModelIfDone(int queryType) {
        synchronized (this) {
            mOutstandingQueries &= ~queryType;
            if (mOutstandingQueries == 0) {
                if (mRestoreModel != null && (!mExistingEvent || (mOriginalModel != null
                        && mRestoreModel.mId == mOriginalModel.mId
                        && EditEventHelper.canModifyEvent(mOriginalModel)))) {
                    mModel = mRestoreModel;
                } else if (mExistingEvent && mRestoreModel != null && mOriginalModel != null
                        && mRestoreModel.mId == mOriginalModel.mId) {
                    // Restore only separately permitted fields, never stale event write permissions.
                    if (EditEventHelper.canAddReminders(mModel)) mModel.mReminders = mRestoreModel.mReminders;
                    if (EditEventHelper.canRespond(mModel)) mModel.mSelfAttendeeStatus = mRestoreModel.mSelfAttendeeStatus;
                    if (EditEventHelper.canModifyCalendar(mModel) && mRestoreModel.isEventColorInitialized())
                        mModel.setEventColor(mRestoreModel.getEventColor());
                }
                boolean editable = EditEventHelper.canModifyEvent(mModel);
                if (!editable) mModification = Utils.MODIFY_UNINITIALIZED;
                else if (mModification == Utils.MODIFY_UNINITIALIZED) {
                    if (mModel.hasRecurrence() && mShowModifyDialogOnLaunch) {
                        displayEditWhichDialog();
                    } else if (!mModel.hasRecurrence()) {
                        mModification = Utils.MODIFY_ALL;
                    }
                }
                if (mExistingEvent && mActivity != null) {
                    int response = mActivity.getIntent().getIntExtra(Attendees.ATTENDEE_STATUS,
                            Attendees.ATTENDEE_STATUS_NONE);
                    if (mRestoreModel == null && response != Attendees.ATTENDEE_STATUS_NONE
                            && EditEventHelper.canRespond(mModel)) mModel.mSelfAttendeeStatus = response;
                    if (mActivity.getSupportActionBar() != null) mActivity.getSupportActionBar()
                            .setTitle(editable ? R.string.event_edit : R.string.event_info_title);
                }
                mView.setModel(mModel);
                mView.setModification(mModification);
                mActivity.invalidateOptionsMenu();
            }
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Fragment restored = getParentFragmentManager().findFragmentByTag(COLOR_PICKER_DIALOG_TAG);
        if (restored instanceof EventColorPickerDialog picker) {
            picker.setOnColorSelectedListener(this);
        }
    }

    private void startQuery() {
        mUri = null;
        mBegin = -1;
        mEnd = -1;
        if (mEvent != null) {
            if (mEvent.id != -1) {
                mModel.mId = mEvent.id;
                mUri = ContentUris.withAppendedId(Events.CONTENT_URI, mEvent.id);
            } else {
                // New event. All day?
                mModel.mAllDay = mEvent.extraLong == CalendarController.EXTRA_CREATE_ALL_DAY;
            }
            if (mEvent.startTime != null) {
                mBegin = mEvent.startTime.toMillis();
            }
            if (mEvent.endTime != null) {
                mEnd = mEvent.endTime.toMillis();
            }
            if (mEvent.calendarId != -1) {
                mCalendarId = mEvent.calendarId;
            }
        } else if (mEventBundle != null) {
            if (mEventBundle.id != -1) {
                mModel.mId = mEventBundle.id;
                mUri = ContentUris.withAppendedId(Events.CONTENT_URI, mEventBundle.id);
            }
            mBegin = mEventBundle.start;
            mEnd = mEventBundle.end;
        }

        if (mReminders != null) {
            mModel.mReminders = mReminders;
        }

        if (mEventColorInitialized) {
            mModel.setEventColor(mEventColor);
        }

        if (mUri == null) {
            if (mBegin <= 0) mBegin = mHelper.constructDefaultStartTime(System.currentTimeMillis());
            if (mEnd < mBegin) mEnd = mHelper.constructDefaultEndTime(mBegin, mActivity);
        }

        // Kick off the query for the event
        boolean newEvent = mUri == null;
        if (!newEvent) {
            mModel.mCalendarAccessLevel = Calendars.CAL_ACCESS_NONE;
            mOutstandingQueries = TOKEN_ALL;
            if (DEBUG) {
                Log.d(TAG, "startQuery: uri for event is " + mUri);
            }
            mHandler.startQuery(TOKEN_EVENT, null, mUri, EditEventHelper.EVENT_PROJECTION,
                    null /* selection */, null /* selection args */, null /* sort order */);
        } else {
            mOutstandingQueries = TOKEN_CALENDARS | TOKEN_COLORS;
            if (DEBUG) {
                Log.d(TAG, "startQuery: Editing a new event.");
            }
            mModel.mOriginalStart = mBegin;
            mModel.mOriginalEnd = mEnd;
            mModel.mStart = mBegin;
            mModel.mEnd = mEnd;
            mModel.mCalendarId = mCalendarId;
            mModel.mSelfAttendeeStatus = Attendees.ATTENDEE_STATUS_ACCEPTED;

            // Start a query in the background to read the list of calendars and colors
            mHandler.startQuery(TOKEN_CALENDARS, null, Calendars.CONTENT_URI,
                    EditEventHelper.CALENDARS_PROJECTION,
                    EditEventHelper.CALENDARS_WHERE_WRITEABLE_VISIBLE, null /* selection args */,
                    null /* sort order */);

            mHandler.startQuery(TOKEN_COLORS, null, Colors.CONTENT_URI,
                    EditEventHelper.COLORS_PROJECTION,
                    Colors.COLOR_TYPE + "=" + Colors.TYPE_EVENT, null, null);

            mModification = Utils.MODIFY_ALL;
            mView.setModification(mModification);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mActivity = (AppCompatActivity) activity;

        mHelper = new EditEventHelper(activity);
        mHandler = new QueryHandler(activity.getContentResolver());
        mModel = new CalendarEventModel(activity, mIntent);
        mInputMethodManager = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);

        mExistingEvent = mActivity instanceof EditEventActivity
                && ((EditEventActivity) mActivity).isExistingEvent();
        mUseCustomActionBar = !mExistingEvent && !Utils.getConfigBool(mActivity, R.bool.multiple_pane_config);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
//        mActivity.requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        View view;
        if (mIsReadOnly) {
            view = inflater.inflate(R.layout.edit_event_single_column, null);
        } else {
            view = inflater.inflate(R.layout.edit_event, null);
        }
        mView = new EditEventView(mActivity, view, mOnDone);

        if (!Utils.isCalendarPermissionGranted(mActivity, true)) {
            //If permission is not granted
            ((TextView)view.findViewById(R.id.loading_message)).setText(R.string.calendar_permission_not_granted);
        } else {
            startQuery();
        }

        if (mUseCustomActionBar) {
            View actionBarButtons = inflater.inflate(R.layout.edit_event_custom_actionbar,
                    new LinearLayout(mActivity), false);
            View cancelActionView = actionBarButtons.findViewById(R.id.action_cancel);
            cancelActionView.setOnClickListener(mActionBarListener);
            View doneActionView = actionBarButtons.findViewById(R.id.action_done);
            doneActionView.setOnClickListener(mActionBarListener);
            ActionBar.LayoutParams layout = new ActionBar.LayoutParams(ActionBar.LayoutParams.MATCH_PARENT, ActionBar.LayoutParams.MATCH_PARENT);
            mActivity.getSupportActionBar().setCustomView(actionBarButtons, layout);
        }

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (mUseCustomActionBar) {
            mActivity.getSupportActionBar().setCustomView(null);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(EditEventFragment.this.getActivity(),
                Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(EditEventFragment.this.getActivity(), new String[]{Manifest.permission.READ_CONTACTS},
                0);
        }

        if (savedInstanceState != null) {
            mExportText = savedInstanceState.getString("event_export_text");
            if (savedInstanceState.containsKey(BUNDLE_KEY_MODEL)) {
                mRestoreModel = (CalendarEventModel) savedInstanceState.getSerializable(
                        BUNDLE_KEY_MODEL);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_EDIT_STATE)) {
                mModification = savedInstanceState.getInt(BUNDLE_KEY_EDIT_STATE);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_EDIT_ON_LAUNCH)) {
                mShowModifyDialogOnLaunch = savedInstanceState
                        .getBoolean(BUNDLE_KEY_EDIT_ON_LAUNCH);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_EVENT)) {
                mEventBundle = (EventBundle) savedInstanceState.getSerializable(BUNDLE_KEY_EVENT);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_READ_ONLY)) {
                mIsReadOnly = savedInstanceState.getBoolean(BUNDLE_KEY_READ_ONLY);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_SHOW_COLOR_PALETTE)) {
                mShowColorPalette = savedInstanceState.getBoolean(BUNDLE_KEY_SHOW_COLOR_PALETTE);
            }

        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        if (!mUseCustomActionBar) {
            inflater.inflate(R.menu.edit_event_title_bar, menu);
            if (mExistingEvent) {
                menu.add(0, R.id.info_action_duplicate, 10, R.string.duplicate_label);
                menu.add(0, R.id.info_action_share_event, 11, R.string.share_label);
                menu.add(0, R.id.info_action_export, 12, R.string.cal_export_event_title);
                menu.add(0, R.id.info_action_delete, 13, R.string.delete_label);
            }
        }
    }

    private boolean existingReady() {
        return mExistingEvent && mOutstandingQueries == 0 && mOriginalModel != null && mModel != null;
    }

    @Override public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        if (!mExistingEvent) return;
        boolean ready = existingReady();
        MenuItem done = menu.findItem(R.id.action_done);
        if (done != null) done.setVisible(ready && (EditEventHelper.canModifyEvent(mModel)
                || EditEventHelper.canRespond(mModel) || EditEventHelper.canAddReminders(mModel)
                || EditEventHelper.canModifyCalendar(mModel)));
        for (int id : new int[]{R.id.info_action_duplicate, R.id.info_action_share_event, R.id.info_action_export}) {
            MenuItem item = menu.findItem(id);
            if (item != null) item.setVisible(ready);
        }
        MenuItem delete = menu.findItem(R.id.info_action_delete);
        if (delete != null) delete.setVisible(ready && EditEventHelper.canModifyCalendar(mOriginalModel));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return onActionBarItemSelected(item.getItemId());
    }

    /**
     * Handles menu item selections, whether they come from our custom action bar buttons or from
     * the standard menu items. Depends on the menu item ids matching the custom action bar button
     * ids.
     *
     * @param itemId the button or menu item id
     * @return whether the event was handled here
     */
    private boolean onActionBarItemSelected(int itemId) {
        if (itemId == R.id.info_action_duplicate || itemId == R.id.info_action_share_event
                || itemId == R.id.info_action_export || itemId == R.id.info_action_delete) {
            if (!existingReady()) return true;
            if (itemId == R.id.info_action_duplicate) duplicateExisting();
            else if (itemId == R.id.info_action_delete) {
                if (EditEventHelper.canModifyCalendar(mOriginalModel)) {
                    new DeleteEventHelper(mActivity, mActivity, true)
                            .delete(mBegin, mEnd, mOriginalModel, -1);
                }
            } else shareExisting(itemId == R.id.info_action_export);
            return true;
        }
        if (itemId == R.id.action_done) {
            if (mExistingEvent && !existingReady()) return true;
            if (mExistingEvent && !EditEventHelper.canModifyEvent(mModel)) {
                saveViewerChanges();
                return true;
            }
            if (EditEventHelper.canModifyEvent(mModel) || EditEventHelper.canRespond(mModel)) {
                if (mView != null && mView.prepareForSave()) {
                    if (mModification == Utils.MODIFY_UNINITIALIZED) {
                        mModification = Utils.MODIFY_ALL;
                    }
                    mOnDone.setDoneCode(Utils.DONE_SAVE | Utils.DONE_EXIT);
                    mOnDone.run();
                } else {
                    mOnDone.setDoneCode(Utils.DONE_REVERT);
                    mOnDone.run();
                }
            } else if (EditEventHelper.canAddReminders(mModel) && mModel.mId != -1
                    && mOriginalModel != null && mView.prepareForSave()) {
                saveReminders();
                mOnDone.setDoneCode(Utils.DONE_EXIT);
                mOnDone.run();
            } else {
                mOnDone.setDoneCode(Utils.DONE_REVERT);
                mOnDone.run();
            }
        } else if (itemId == R.id.action_cancel) {
            mOnDone.setDoneCode(Utils.DONE_REVERT);
            mOnDone.run();
        }
        return itemId == R.id.action_done || itemId == R.id.action_cancel;
    }

    // An invitation/readonly calendar may allow personal reminders, response or color,
    // but none of these operations should rewrite the event title, dates or recurrence.
    private void saveViewerChanges() {
        if (!mView.fillModelFromReadOnlyUi()) return;
        boolean responseChanged = EditEventHelper.canRespond(mModel) && mModel.mOwnerAttendeeId != -1
                && mModel.mSelfAttendeeStatus != Attendees.ATTENDEE_STATUS_NONE
                && mModel.mSelfAttendeeStatus != mOriginalModel.mSelfAttendeeStatus;
        if (responseChanged && mModel.hasRecurrence()) {
            new MaterialAlertDialogBuilder(mActivity).setTitle(R.string.change_response_title)
                    .setItems(new CharSequence[]{getString(R.string.modify_event), getString(R.string.modify_all)},
                            (dialog, which) -> finishViewerSave(true, which == 0))
                    .setNegativeButton(android.R.string.cancel, null).show();
        } else finishViewerSave(responseChanged, false);
    }

    private void finishViewerSave(boolean responseChanged, boolean single) {
        if (EditEventHelper.canAddReminders(mModel)) saveReminders();
        if (responseChanged) {
            ArrayList<ContentProviderOperation> operations = new ArrayList<>();
            ContentValues values = new ContentValues();
            if (single) {
                values.put(Events.ORIGINAL_INSTANCE_TIME, mBegin);
                values.put(Events.SELF_ATTENDEE_STATUS, mModel.mSelfAttendeeStatus);
                values.put(Events.STATUS, Events.STATUS_CONFIRMED);
                operations.add(ContentProviderOperation.newInsert(
                        Uri.withAppendedPath(Events.CONTENT_EXCEPTION_URI, Long.toString(mModel.mId)))
                        .withValues(values).build());
            } else {
                values.put(Attendees.EVENT_ID, mModel.mId);
                values.put(Attendees.ATTENDEE_STATUS, mModel.mSelfAttendeeStatus);
                if (!TextUtils.isEmpty(mModel.mOwnerAccount)) values.put(Attendees.ATTENDEE_EMAIL, mModel.mOwnerAccount);
                operations.add(ContentProviderOperation.newUpdate(
                        ContentUris.withAppendedId(Attendees.CONTENT_URI, mModel.mOwnerAttendeeId))
                        .withValues(values).build());
            }
            new AsyncQueryService(mActivity).startBatch(0, null,
                    android.provider.CalendarContract.AUTHORITY, operations, 0);
        }
        if (EditEventHelper.canModifyCalendar(mModel) && mModel.isEventColorInitialized()
                && mModel.getEventColor() != mOriginalModel.getEventColor()) {
            ContentValues values = new ContentValues();
            String colorKey = mModel.getEventColorKey();
            values.put(Events.EVENT_COLOR_KEY, mModel.getEventColor() == mModel.getCalendarColor()
                    || colorKey == null ? "" : colorKey);
            new AsyncQueryService(mActivity).startUpdate(0, null,
                    ContentUris.withAppendedId(Events.CONTENT_URI, mModel.mId),
                    values, null, null, 0);
        }
        mSaveOnDetach = false;
        mActivity.finish();
    }

    private void duplicateExisting() {
        CalendarEventModel source = mOriginalModel;
        Intent intent = new Intent(mActivity, EditEventActivity.class);
        intent.setType("vnd.android.cursor.item/event");
        if (EditEventHelper.canModifyCalendar(source)) intent.putExtra(Events.CALENDAR_ID, source.mCalendarId);
        intent.putExtra(Events.TITLE, source.mTitle);
        intent.putExtra(Events.DESCRIPTION, source.mDescription);
        intent.putExtra(Events.EVENT_LOCATION, source.mLocation);
        intent.putExtra(Events.ORGANIZER, source.mOrganizer);
        intent.putExtra(Events.RRULE, source.mRrule);
        intent.putExtra(Events.ACCESS_LEVEL, source.mAccessLevel);
        intent.putExtra(Events.AVAILABILITY, source.mAvailability);
        intent.putExtra(Events.ALL_DAY, source.mAllDay);
        intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, source.mAllDay);
        intent.putExtra(Events.EVENT_TIMEZONE, source.mTimezone);
        intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, mBegin);
        intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, mEnd);
        intent.putExtra(EditEventActivity.EXTRA_EVENT_REMINDERS, source.mReminders);
        intent.putExtra(EditEventActivity.EXTRA_EVENT_COLOR, source.getEventColor());
        intent.putExtra(ExtendedProperty.URL, source.mUrl);
        ArrayList<String> emails = new ArrayList<>();
        for (Attendee attendee : source.mAttendeesList.values()) if (!TextUtils.isEmpty(attendee.mEmail)) emails.add(attendee.mEmail);
        intent.putExtra(Intent.EXTRA_EMAIL, TextUtils.join(",", emails));
        startActivity(intent);
    }

    private void shareExisting(boolean export) {
        CalendarEventModel source = mOriginalModel;
        com.android.calendar.icalendar.VCalendar calendar = new com.android.calendar.icalendar.VCalendar();
        calendar.addProperty(com.android.calendar.icalendar.VCalendar.VERSION, "2.0");
        calendar.addProperty(com.android.calendar.icalendar.VCalendar.PRODID,
                com.android.calendar.icalendar.VCalendar.PRODUCT_IDENTIFIER);
        calendar.addProperty(com.android.calendar.icalendar.VCalendar.CALSCALE, "GREGORIAN");
        calendar.addProperty(com.android.calendar.icalendar.VCalendar.METHOD, "REQUEST");
        com.android.calendar.icalendar.VEvent event = new com.android.calendar.icalendar.VEvent();
        String tz = source.mAllDay || TextUtils.isEmpty(source.mTimezone) ? "UTC" : source.mTimezone;
        event.addEventStart(mBegin, tz);
        event.addEventEnd(mEnd, tz);
        event.addProperty(com.android.calendar.icalendar.VEvent.SUMMARY, source.mTitle);
        event.addProperty(com.android.calendar.icalendar.VEvent.LOCATION, source.mLocation);
        event.addProperty(com.android.calendar.icalendar.VEvent.DESCRIPTION, source.mDescription);
        if (!TextUtils.isEmpty(source.mOrganizer)) event.addOrganizer(
                new com.android.calendar.icalendar.Organizer(source.mOrganizerDisplayName, source.mOrganizer));
        for (Attendee attendee : source.mAttendeesList.values())
            com.android.calendar.icalendar.IcalendarUtils.addAttendeeToEvent(attendee, event);
        calendar.addEvent(event);
        String prefix = source.mTitle == null ? "invite" : source.mTitle.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (prefix.length() < 3) prefix = "invite";
        if (prefix.length() > 80) prefix = prefix.substring(0,80);
        if (export) {
            mExportText = calendar.getICalFormattedString();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/calendar").putExtra(Intent.EXTRA_TITLE, prefix + ".ics");
            mExportLauncher.launch(intent);
            return;
        }
        try {
            java.io.File file = java.io.File.createTempFile(prefix, ".ics", mActivity.getExternalCacheDir());
            if (!com.android.calendar.icalendar.IcalendarUtils.writeCalendarToFile(calendar, file))
                throw new java.io.IOException("Failed to write calendar");
            Uri uri = androidx.core.content.FileProvider.getUriForFile(mActivity,
                    ws.xsoh.etar.BuildConfig.APPLICATION_ID + ".provider", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/calendar")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri("calendar", uri));
            startActivity(Intent.createChooser(send, getString(R.string.cal_share_intent_title)));
        } catch (java.io.IOException | IllegalArgumentException error) {
            Toast.makeText(mActivity, R.string.error_generating_ics, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveReminders() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>(3);
        boolean changed = EditEventHelper.saveReminders(ops, mModel.mId, mModel.mReminders,
                mOriginalModel.mReminders, false /* no force save */);

        if (!changed) {
            return;
        }

        AsyncQueryService service = new AsyncQueryService(getActivity());
        service.startBatch(0, null, Calendars.CONTENT_URI.getAuthority(), ops, 0);
        // Update the "hasAlarm" field for the event
        Uri uri = ContentUris.withAppendedId(Events.CONTENT_URI, mModel.mId);
        int len = mModel.mReminders.size();
        boolean hasAlarm = len > 0;
        if (hasAlarm != mOriginalModel.mHasAlarm) {
            ContentValues values = new ContentValues();
            values.put(Events.HAS_ALARM, hasAlarm ? 1 : 0);
            service.startUpdate(0, null, uri, values, null, null, 0);
        }

        Toast.makeText(mActivity, R.string.saving_event, Toast.LENGTH_SHORT).show();
    }

    protected void displayEditWhichDialog() {
        if (mModification == Utils.MODIFY_UNINITIALIZED) {
            if (!TextUtils.isEmpty(mModel.mRdate)) {
                if (!TextUtils.isEmpty(mModel.mRrule)) {
                    mModification = Utils.MODIFY_ALL;
                    return;
                }
                final boolean first = mModel.mIsFirstEventInSeries;
                CharSequence[] choices = first
                        ? new CharSequence[]{mActivity.getText(R.string.modify_event), mActivity.getText(R.string.modify_all)}
                        : new CharSequence[]{mActivity.getText(R.string.modify_event), mActivity.getText(R.string.modify_all),
                                mActivity.getText(R.string.modify_all_following)};
                mModifyDialog = new MaterialAlertDialogBuilder(mActivity)
                        .setTitle(R.string.edit_event_label)
                        .setItems(choices, (dialog, which) -> {
                            mModification = which == 0 ? Utils.MODIFY_SELECTED
                                    : which == 1 ? Utils.MODIFY_ALL : Utils.MODIFY_ALL_FOLLOWING;
                            mView.setModification(mModification);
                        }).setOnCancelListener(dialog -> mActivity.finish()).show();
                return;
            }
            final boolean notSynced = TextUtils.isEmpty(mModel.mSyncId);
            boolean isFirstEventInSeries = mModel.mIsFirstEventInSeries;
            int itemIndex = 0;
            CharSequence[] items;

            if (notSynced) {
                // If this event has not been synced, then don't allow deleting
                // or changing a single instance.
                if (isFirstEventInSeries) {
                    // Still display the option so the user knows all events are
                    // changing
                    items = new CharSequence[1];
                } else {
                    items = new CharSequence[2];
                }
            } else {
                if (isFirstEventInSeries) {
                    items = new CharSequence[2];
                } else {
                    items = new CharSequence[3];
                }
                items[itemIndex++] = mActivity.getText(R.string.modify_event);
            }
            items[itemIndex++] = mActivity.getText(R.string.modify_all);

            // Do one more check to make sure this remains at the end of the list
            if (!isFirstEventInSeries) {
                items[itemIndex++] = mActivity.getText(R.string.modify_all_following);
            }

            // Display the modification dialog.
            if (mModifyDialog != null) {
                mModifyDialog.dismiss();
                mModifyDialog = null;
            }
            mModifyDialog = new MaterialAlertDialogBuilder(mActivity)
                    .setTitle(R.string.edit_event_label)
                    .setItems(items, new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) {
                                // Update this if we start allowing exceptions
                                // to unsynced events in the app
                                mModification = notSynced ? Utils.MODIFY_ALL
                                        : Utils.MODIFY_SELECTED;
                                if (mModification == Utils.MODIFY_SELECTED) {
                                    mModel.mOriginalSyncId = notSynced ? null : mModel.mSyncId;
                                    mModel.mOriginalId = mModel.mId;
                                }
                            } else if (which == 1) {
                                mModification = notSynced ? Utils.MODIFY_ALL_FOLLOWING
                                        : Utils.MODIFY_ALL;
                            } else if (which == 2) {
                                mModification = Utils.MODIFY_ALL_FOLLOWING;
                            }

                            mView.setModification(mModification);
                        }
                    }).show();

            mModifyDialog.setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    Activity a = EditEventFragment.this.getActivity();
                    if (a != null) {
                        a.finish();
                    }
                }
            });
        }
    }

    boolean isEmptyNewEvent() {
        if (mOriginalModel != null) {
            // Not new
            return false;
        }

        if (mModel.mOriginalStart != mModel.mStart || mModel.mOriginalEnd != mModel.mEnd) {
            return false;
        }

        if (!mModel.mAttendeesList.isEmpty()) {
            return false;
        }

        return mModel.isEmpty();
    }

    public void onBackPressed() {
        if (mExistingEvent && (!existingReady() || !EditEventHelper.canModifyEvent(mModel))) {
            if (existingReady() && mView.fillModelFromReadOnlyUi()
                    && (!mModel.mReminders.equals(mOriginalModel.mReminders)
                    || mModel.mSelfAttendeeStatus != mOriginalModel.mSelfAttendeeStatus
                    || mModel.getEventColor() != mOriginalModel.getEventColor())) {
                showDiscardConfirmAlert();
            } else {
                mSaveOnDetach = false;
                mActivity.finish();
            }
            return;
        }
        if (mExistingEvent && existingReady() && mView.prepareForSave()
                && mModel.isUnchanged(mOriginalModel)) {
            mSaveOnDetach = false;
            mActivity.finish();
            return;
        }
        if (canSave()) {
            showDiscardConfirmAlert();
            return;
        }

        if (mExistingEvent) mActivity.finish();
        else Utils.returnToCalendarHome(getActivity());
    }

    private boolean canSave() {
        Activity act = getActivity();
        return mSaveOnDetach && act != null && !mIsReadOnly && !act.isChangingConfigurations()
               && mView.prepareForSave();
    }

    private void showDiscardConfirmAlert() {
        com.android.calendar.ItemEditorUi.confirmDiscard(getActivity(), () -> {
            revertEventChanges();
            if (mExistingEvent) mActivity.finish();
            else Utils.returnToCalendarHome(getActivity());
        });
    }

    private void revertEventChanges() {
            mOnDone.setDoneCode(Utils.DONE_REVERT);
            mOnDone.run();
    }

    @Override
    public void onDestroy() {
        if (mView != null) {
            mView.setModel(null);
        }
        if (mModifyDialog != null) {
            mModifyDialog.dismiss();
            mModifyDialog = null;
        }
        super.onDestroy();
    }

    @Override
    public void eventsChanged() {
        // TODO Requery to see if event has changed
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (mExistingEvent && existingReady() && !EditEventHelper.canModifyEvent(mModel))
            mView.fillModelFromReadOnlyUi();
        else mView.prepareForSave();
        outState.putString("event_export_text", mExportText);
        outState.putSerializable(BUNDLE_KEY_MODEL, mModel);
        outState.putInt(BUNDLE_KEY_EDIT_STATE, mModification);
        if (mEventBundle == null && mEvent != null) {
            mEventBundle = new EventBundle();
            mEventBundle.id = mEvent.id;
            if (mEvent.startTime != null) {
                mEventBundle.start = mEvent.startTime.toMillis();
            }
            if (mEvent.endTime != null) {
                mEventBundle.end = mEvent.endTime.toMillis();
            }
        }
        outState.putBoolean(BUNDLE_KEY_EDIT_ON_LAUNCH, mShowModifyDialogOnLaunch);
        outState.putSerializable(BUNDLE_KEY_EVENT, mEventBundle);
        outState.putBoolean(BUNDLE_KEY_READ_ONLY, mIsReadOnly);
        outState.putBoolean(BUNDLE_KEY_SHOW_COLOR_PALETTE, mView.isColorPaletteVisible());
    }

    @Override
    public long getSupportedEventTypes() {
        return EventType.USER_HOME;
    }

    @Override
    public void handleEvent(EventInfo event) {
        // It's currently unclear if we want to save the event or not when home
        // is pressed. When creating a new event we shouldn't save since we
        // can't get the id of the new event easily.
        if ((false && event.eventType == EventType.USER_HOME) || (event.eventType == EventType.GO_TO
                && mSaveOnDetach)) {
            if (mView != null && mView.prepareForSave()) {
                mOnDone.setDoneCode(Utils.DONE_SAVE);
                mOnDone.run();
            }
        }
    }

    @Override
    public void onColorSelected(int color) {
        if (!mModel.isEventColorInitialized() || mModel.getEventColor() != color) {
            mModel.setEventColor(color);
            mView.updateHeadlineColor(color);
        }
    }

    private static class EventBundle implements Serializable {
        private static final long serialVersionUID = 1L;
        long id = -1;
        long start = -1;
        long end = -1;
    }

    // TODO turn this into a helper function in EditEventHelper for building the
    // model
    private class QueryHandler extends AsyncQueryHandler {
        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            // If the query didn't return a cursor for some reason return
            if (cursor == null) {
                return;
            }

            // If the Activity is finishing, then close the cursor.
            // Otherwise, use the new cursor in the adapter.
            final Activity activity = EditEventFragment.this.getActivity();
            if (activity == null || activity.isFinishing()) {
                cursor.close();
                return;
            }
            long eventId;
            switch (token) {
                case TOKEN_EVENT:
                    if (!cursor.moveToFirst()) {
                        // The cursor is empty. This can happen if the event
                        // was deleted.
                        cursor.close();
                        mOnDone.setDoneCode(Utils.DONE_EXIT);
                        mSaveOnDetach = false;
                        mOnDone.run();
                        return;
                    }
                    mOriginalModel = new CalendarEventModel();
                    EditEventHelper.setModelFromCursor(mOriginalModel, cursor, activity);
                    EditEventHelper.setModelFromCursor(mModel, cursor, activity);
                    mModel.mCountdown = com.android.calendar.EventCountdown.enabled(activity, mModel.mId);
                    mOriginalModel.mCountdown = mModel.mCountdown;
                    cursor.close();

                    mOriginalModel.mUri = mUri.toString();

                    if (mBegin <= 0) mBegin = mOriginalModel.mStart;
                    if (mEnd < mBegin) {
                        if (mOriginalModel.hasRecurrence() && !TextUtils.isEmpty(mOriginalModel.mDuration)) {
                            try {
                                com.android.calendar.calendarcommon2.Duration duration = new com.android.calendar.calendarcommon2.Duration();
                                duration.parse(mOriginalModel.mDuration);
                                mEnd = mBegin + duration.getMillis();
                            } catch (com.android.calendar.calendarcommon2.DateException ignored) {
                                mEnd = mBegin;
                            }
                        } else {
                            mEnd = Math.max(mBegin, mOriginalModel.mEnd);
                        }
                    }
                    if (mEnd < mBegin) mEnd = mBegin;
                    mModel.mUri = mUri.toString();
                    mModel.mOriginalStart = mBegin;
                    mModel.mOriginalEnd = mEnd;
                    mModel.mIsFirstEventInSeries = mBegin == mOriginalModel.mStart;
                    mModel.mStart = mBegin;
                    mModel.mEnd = mEnd;
                    if (mEventColorInitialized) {
                        mModel.setEventColor(mEventColor);
                    }
                    eventId = mModel.mId;

                    // TOKEN_ATTENDEES
                    if (mModel.mHasAttendeeData && eventId != -1) {
                        Uri attUri = Attendees.CONTENT_URI;
                        String[] whereArgs = {
                                Long.toString(eventId)
                        };
                        mHandler.startQuery(TOKEN_ATTENDEES, null, attUri,
                                EditEventHelper.ATTENDEES_PROJECTION,
                                EditEventHelper.ATTENDEES_WHERE /* selection */,
                                whereArgs /* selection args */, null /* sort order */);
                    } else {
                        setModelIfDone(TOKEN_ATTENDEES);
                    }

                    // TOKEN_REMINDERS
                    if (mModel.mHasAlarm && mReminders == null) {
                        Uri rUri = Reminders.CONTENT_URI;
                        String[] remArgs = {
                                Long.toString(eventId)
                        };
                        mHandler.startQuery(TOKEN_REMINDERS, null, rUri,
                                EditEventHelper.REMINDERS_PROJECTION,
                                EditEventHelper.REMINDERS_WHERE /* selection */,
                                remArgs /* selection args */, null /* sort order */);
                    } else {
                        if (mReminders == null) {
                            // mReminders should not be null.
                            mReminders = new ArrayList<ReminderEntry>();
                        } else {
                            Collections.sort(mReminders);
                        }
                        mOriginalModel.mReminders = mReminders;
                        mModel.mReminders =
                                (ArrayList<ReminderEntry>) mReminders.clone();
                        setModelIfDone(TOKEN_REMINDERS);
                    }

                    final String selection;
                    final String[] selectionArgs;
                    final boolean isRecurring = mModel.hasRecurrence();
                    if (isRecurring) {
                        // Enable changing calendars to synced
                        // calendars only, as we currently don't allow recurrence exceptions in local calendars
                        // and would lose them when moving the recurrence between calendars
                        selection = EditEventHelper.CALENDARS_SELECTION_FOR_MOVING_RECURRENCE;
                        selectionArgs = new String[] { Long.toString(mModel.mCalendarId) };
                    } else {
                        // non recurring event. enable changing calendars to all calendars.
                        selection = "(" + EditEventHelper.CALENDARS_WHERE + ") OR ("
                                + EditEventHelper.CALENDARS_WHERE_WRITEABLE_VISIBLE + ")";
                        selectionArgs = new String[] { Long.toString(mModel.mCalendarId) };
                    }

                    // TOKEN_CALENDARS
                    mHandler.startQuery(TOKEN_CALENDARS, null, Calendars.CONTENT_URI,
                            EditEventHelper.CALENDARS_PROJECTION,
                            selection,
                            selectionArgs,
                            null /* sort order */);

                    // TOKEN_COLORS
                    mHandler.startQuery(TOKEN_COLORS, null, Colors.CONTENT_URI,
                            EditEventHelper.COLORS_PROJECTION,
                            Colors.COLOR_TYPE + "=" + Colors.TYPE_EVENT, null, null);

                    // TOKEN_EXTENDED
                    Uri extendedPropUri = ExtendedProperty.contentUri(
                            mModel.mCalendarAccountName,
                            mModel.mCalendarAccountType
                    );
                    String[] selArgs = new String[]{ Long.toString(eventId) };
                    mHandler.startQuery(TOKEN_EXTENDED, null, extendedPropUri,
                            EditEventHelper.EXTENDED_PROJECTION,
                            EditEventHelper.EXTENDED_WHERE_EVENT, selArgs, null);

                    setModelIfDone(TOKEN_EVENT);
                    break;
                case TOKEN_ATTENDEES:
                    try (cursor) {
                        while (cursor.moveToNext()) {
                            String name = cursor.getString(EditEventHelper.ATTENDEES_INDEX_NAME);
                            String email = cursor.getString(EditEventHelper.ATTENDEES_INDEX_EMAIL);
                            int status = cursor.getInt(EditEventHelper.ATTENDEES_INDEX_STATUS);
                            int relationship = cursor
                                    .getInt(EditEventHelper.ATTENDEES_INDEX_RELATIONSHIP);
                            if (relationship == Attendees.RELATIONSHIP_ORGANIZER) {
                                if (email != null) {
                                    mModel.mOrganizer = email;
                                    mModel.mIsOrganizer = mModel.mOwnerAccount
                                            .equalsIgnoreCase(email);
                                    mOriginalModel.mOrganizer = email;
                                    mOriginalModel.mIsOrganizer = mOriginalModel.mOwnerAccount
                                            .equalsIgnoreCase(email);
                                }

                                if (TextUtils.isEmpty(name)) {
                                    mModel.mOrganizerDisplayName = mModel.mOrganizer;
                                    mOriginalModel.mOrganizerDisplayName =
                                            mOriginalModel.mOrganizer;
                                } else {
                                    mModel.mOrganizerDisplayName = name;
                                    mOriginalModel.mOrganizerDisplayName = name;
                                }
                            }

                            if (email != null) {
                                if (mModel.mOwnerAccount != null &&
                                        mModel.mOwnerAccount.equalsIgnoreCase(email)) {
                                    int attendeeId =
                                            cursor.getInt(EditEventHelper.ATTENDEES_INDEX_ID);
                                    mModel.mOwnerAttendeeId = attendeeId;
                                    mModel.mSelfAttendeeStatus = status;
                                    mOriginalModel.mOwnerAttendeeId = attendeeId;
                                    mOriginalModel.mSelfAttendeeStatus = status;
                                    continue;
                                }
                            }
                            Attendee attendee = new Attendee(name, email);
                            attendee.mStatus = status;
                            mModel.addAttendee(attendee);
                            mOriginalModel.addAttendee(attendee);
                        }
                    }

                    setModelIfDone(TOKEN_ATTENDEES);
                    break;
                case TOKEN_REMINDERS:
                    try (cursor) {
                        // Add all reminders to the models
                        while (cursor.moveToNext()) {
                            int minutes = cursor.getInt(EditEventHelper.REMINDERS_INDEX_MINUTES);
                            int method = cursor.getInt(EditEventHelper.REMINDERS_INDEX_METHOD);
                            ReminderEntry re = ReminderEntry.valueOf(minutes, method);
                            mModel.mReminders.add(re);
                            mOriginalModel.mReminders.add(re);
                        }

                        // Sort appropriately for display
                        Collections.sort(mModel.mReminders);
                        Collections.sort(mOriginalModel.mReminders);
                    }

                    setModelIfDone(TOKEN_REMINDERS);
                    break;
                case TOKEN_CALENDARS:
                    try (cursor) {
                        MatrixCursor matrixCursor = Utils.matrixCursorFromCursor(cursor);
                        if (DEBUG) {
                            Log.d(TAG, "onQueryComplete: setting cursor with " + matrixCursor.getCount() + " calendars");
                        }
                        if (mModel.mId != -1) {
                            // Populate model for an existing event
                            // Missing source calendars must never inherit a writable default.
                            mModel.mCalendarAccessLevel = Calendars.CAL_ACCESS_NONE;
                            mOriginalModel.mCalendarAccessLevel = Calendars.CAL_ACCESS_NONE;
                            EditEventHelper.setModelFromCalendarCursor(mModel, cursor, activity);
                            EditEventHelper.setModelFromCalendarCursor(mOriginalModel, cursor, activity);
                        }
                        mView.setCalendarsCursor(matrixCursor, isAdded() && isResumed(), mModel.mCalendarId);
                    }
                    setModelIfDone(TOKEN_CALENDARS);
                    break;
                case TOKEN_COLORS:
                    try (cursor) {
                        if (cursor.moveToFirst()) {
                            EventColorCache cache = new EventColorCache();
                            do {
                                String colorKey = cursor.getString(EditEventHelper.COLORS_INDEX_COLOR_KEY);
                                int rawColor = cursor.getInt(EditEventHelper.COLORS_INDEX_COLOR);
                                int displayColor = rawColor;
                                String accountName = cursor
                                        .getString(EditEventHelper.COLORS_INDEX_ACCOUNT_NAME);
                                String accountType = cursor
                                        .getString(EditEventHelper.COLORS_INDEX_ACCOUNT_TYPE);
                                cache.insertColor(accountName, accountType,
                                        displayColor, colorKey);
                            } while (cursor.moveToNext());
                            cache.sortPalettes(new HsvColorComparator());

                            mModel.mEventColorCache = cache;
                            mView.mColorPicker.setOnClickListener(mOnColorPickerClicked);
                        }
                    }

                    // If the account name/type is null, the calendar event colors cannot be
                    // determined, so take the default/savedInstanceState value.
                    if (mModel.mCalendarAccountName == null
                            || mModel.mCalendarAccountType == null) {
                        mView.setColorPickerButtonStates(mShowColorPalette);
                    } else {
                        mView.setColorPickerButtonStates(mModel.getCalendarEventColors());
                    }

                    setModelIfDone(TOKEN_COLORS);
                    break;
                case TOKEN_EXTENDED:
                    while(cursor.moveToNext()) {
                        String name = cursor.getString(EXTENDED_INDEX_NAME);
                        String value = cursor.getString(EXTENDED_INDEX_VALUE);
                        switch (name) {
                            case ExtendedProperty.URL_NAME:
                            case ExtendedProperty.URL_NAME_PRIV:
                                mModel.mUrl = value;
                                mOriginalModel.mUrl = value;
                                if (value != null) {
                                    mView.mUrlTextView.setTextKeepState(mModel.mUrl);
                                }
                                break;
                        }
                    }

                    if (cursor != null) {
                        cursor.close();
                    }

                    setModelIfDone(TOKEN_EXTENDED);
                    break;
                default:
                    cursor.close();
                    break;
            }
        }
    }

    class Done implements EditEventHelper.EditDoneRunnable {
        private int mCode = -1;

        @Override
        public void setDoneCode(int code) {
            mCode = code;
        }

        @Override
        public void run() {
            // We only want this to get called once, either because the user
            // pressed back/home or one of the buttons on screen
            mSaveOnDetach = false;
            if (mModification == Utils.MODIFY_UNINITIALIZED) {
                // If this is uninitialized the user hit back, the only
                // changeable item is response to default to all events.
                mModification = Utils.MODIFY_ALL;
            }

            if ((mCode & Utils.DONE_SAVE) != 0 && mModel != null
                    && EditEventHelper.canModifyEvent(mModel)
                    && mView.prepareForSave()
                    && !isEmptyNewEvent()
                    && mModel.normalizeReminders()
                    && mHelper.saveEvent(mModel, mOriginalModel, mModification)) {
                int stringResource;
                if (mModel.mIsOrganizer && !mModel.mAttendeesList.isEmpty()) {
                    if (mModel.mUri != null) {
                        stringResource = R.string.saving_event_with_guest;
                    } else {
                        stringResource = R.string.creating_event_with_guest;
                    }
                } else {
                    if (mModel.mUri != null) {
                        stringResource = R.string.saving_event;
                    } else {
                        stringResource = R.string.creating_event;
                    }
                }
                Toast.makeText(mActivity, stringResource, Toast.LENGTH_SHORT).show();
            } else if ((mCode & Utils.DONE_SAVE) != 0 && mModel != null && isEmptyNewEvent()) {
                Toast.makeText(mActivity, R.string.empty_event, Toast.LENGTH_SHORT).show();
            }

            if ((mCode & Utils.DONE_DELETE) != 0 && mOriginalModel != null
                    && EditEventHelper.canModifyCalendar(mOriginalModel)) {
                long begin = mModel.mStart;
                long end = mModel.mEnd;
                int which = -1;
                switch (mModification) {
                    case Utils.MODIFY_SELECTED:
                        which = DeleteEventHelper.DELETE_SELECTED;
                        break;
                    case Utils.MODIFY_ALL_FOLLOWING:
                        which = DeleteEventHelper.DELETE_ALL_FOLLOWING;
                        break;
                    case Utils.MODIFY_ALL:
                        which = DeleteEventHelper.DELETE_ALL;
                        break;
                }
                DeleteEventHelper deleteHelper = new DeleteEventHelper(
                        mActivity, mActivity, !mIsReadOnly /* exitWhenDone */);
                deleteHelper.delete(begin, end, mOriginalModel, which);
            }

            if ((mCode & Utils.DONE_EXIT) != 0) {
                // This will exit the edit event screen, should be called
                // when we want to return to the main calendar views
                if ((mCode & Utils.DONE_SAVE) != 0) {
                    if (mActivity != null && !mExistingEvent) {
                        long start = mModel.mStart;
                        long end = mModel.mEnd;
                        if (mModel.mAllDay) {
                            // For allday events we want to go to the day in the
                            // user's current tz
                            String tz = Utils.getTimeZone(mActivity, null);
                            Time t = new Time(Time.TIMEZONE_UTC);
                            t.set(start);
                            t.setTimezone(tz);
                            start = t.toMillis();

                            t.setTimezone(Time.TIMEZONE_UTC);
                            t.set(end);
                            t.setTimezone(tz);
                            end = t.toMillis();
                        }
                        CalendarController.getInstance(mActivity).launchViewEvent(-1, start, end,
                                Attendees.ATTENDEE_STATUS_NONE);
                    }
                }
                Activity a = EditEventFragment.this.getActivity();
                if (a != null) {
                    a.finish();
                }
            }

            // Hide a software keyboard so that user won't see it even after this Fragment's
            // disappearing.
            final View focusedView = mActivity.getCurrentFocus();
            if (focusedView != null) {
                mInputMethodManager.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
            }
        }
    }
}
