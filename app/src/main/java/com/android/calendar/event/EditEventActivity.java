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

import static android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY;
import static android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME;
import static android.provider.CalendarContract.EXTRA_EVENT_END_TIME;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract.Events;
import android.util.Log;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.FragmentTransaction;

import com.android.calendar.AbstractCalendarActivity;
import com.android.calendar.CalendarController;
import com.android.calendar.CalendarController.EventInfo;
import com.android.calendar.CalendarEventModel.ReminderEntry;
import com.android.calendar.Utils;
import com.android.calendar.theme.DynamicThemeKt;
import com.android.calendar.calendarcommon2.Time;

import java.util.ArrayList;

import ws.xsoh.etar.R;
import ws.xsoh.etar.databinding.SimpleFrameLayoutMaterialBinding;

public class EditEventActivity extends AbstractCalendarActivity {
    public static final String EXTRA_EVENT_COLOR = "event_color";
    public static final String EXTRA_EVENT_REMINDERS = "reminders";
    public static final String EXTRA_READ_ONLY = "read_only";
    private static final String TAG = "EditEventActivity";
    private static final boolean DEBUG = false;
    private static final String BUNDLE_KEY_EVENT_ID = "key_event_id";

    private boolean mIsMultipane;

    public static void openEvent(android.content.Context context, long id, long start, long end, int response) {
        Intent intent = new Intent(Intent.ACTION_EDIT,
                android.content.ContentUris.withAppendedId(Events.CONTENT_URI, id),
                context, EditEventActivity.class);
        intent.putExtra(EXTRA_EVENT_BEGIN_TIME, start);
        intent.putExtra(EXTRA_EVENT_END_TIME, end);
        intent.putExtra(android.provider.CalendarContract.Attendees.ATTENDEE_STATUS, response);
        intent.putExtra(CalendarController.EVENT_EDIT_ON_LAUNCH, true);
        if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public boolean isExistingEvent() { return mEventInfo != null && mEventInfo.id > 0; }
    private EditEventFragment mEditFragment;

    private ArrayList<ReminderEntry> mReminders;

    private int mEventColor;

    private boolean mEventColorInitialized;

    private EventInfo mEventInfo;

    @Override
    protected void onCreate(Bundle icicle) {
        DynamicThemeKt.applyTheme(this);
        // Restored fragments attach during super.onCreate(), so initialize the
        // event identity first to keep the same toolbar and permission mode.
        mEventInfo = getEventInfoFromIntent(icicle);
        super.onCreate(icicle);
        if (icicle == null && isExistingEvent()) {
            int notificationId = getIntent().getIntExtra(
                    com.android.calendar.alerts.AlertUtils.NOTIFICATION_ID_KEY, -1);
            if (notificationId != -1) com.android.calendar.alerts.AlertUtils.dismissNotificationAndFiredAlarm(
                    this, mEventInfo.id, notificationId);
        }
        mReminders = getReminderEntriesFromIntent();
        mEventColorInitialized = getIntent().hasExtra(EXTRA_EVENT_COLOR);
        mEventColor = getIntent().getIntExtra(EXTRA_EVENT_COLOR, -1);

        SimpleFrameLayoutMaterialBinding binding = SimpleFrameLayoutMaterialBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.include.toolbar);

        mEditFragment = (EditEventFragment) getSupportFragmentManager().findFragmentById(R.id.body_frame);

        mIsMultipane = Utils.getConfigBool(this, R.bool.multiple_pane_config);

        if (mIsMultipane || isExistingEvent()) {
            getSupportActionBar().setDisplayOptions(
                    ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_HOME_AS_UP,
                    ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_HOME
                            | ActionBar.DISPLAY_SHOW_TITLE);
            getSupportActionBar().setTitle(
                    mEventInfo.id == -1 ? R.string.event_create : R.string.event_edit);
        }
        else {
            getSupportActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,
                    ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_HOME|
                    ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_SHOW_CUSTOM);
        }

        if (mEditFragment == null) {
            Intent intent = null;
            boolean readOnly = false;
            if (mEventInfo.id == -1) {
                intent = getIntent();
                readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false);
            }

            mEditFragment = new EditEventFragment(mEventInfo, mReminders, mEventColorInitialized,
                    mEventColor, readOnly, intent);

            mEditFragment.mShowModifyDialogOnLaunch = isExistingEvent()
                    || getIntent().getBooleanExtra(CalendarController.EVENT_EDIT_ON_LAUNCH, false);

            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.body_frame, mEditFragment);
            ft.show(mEditFragment);
            ft.commit();
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mEditFragment != null) {
                    mEditFragment.onBackPressed();
                } else {
                    if (isEnabled()) {
                        finish();
                    }
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private ArrayList<ReminderEntry> getReminderEntriesFromIntent() {
        Intent intent = getIntent();
        return (ArrayList<ReminderEntry>) intent.getSerializableExtra(EXTRA_EVENT_REMINDERS);
    }

    private EventInfo getEventInfoFromIntent(Bundle icicle) {
        EventInfo info = new EventInfo();
        long eventId = -1;
        Intent intent = getIntent();
        Uri data = intent.getData();
        long linkedBegin = -1, linkedEnd = -1;
        if (data != null) {
            try {
                java.util.List<String> parts = data.getPathSegments();
                if (parts.size() >= 5 && "events".equals(parts.get(0)) && "EventTime".equals(parts.get(2))) {
                    eventId = Long.parseLong(parts.get(1));
                    linkedBegin = Long.parseLong(parts.get(3));
                    linkedEnd = Long.parseLong(parts.get(4));
                } else {
                    eventId = Long.parseLong(data.getLastPathSegment());
                }
            } catch (NumberFormatException e) {
                if (DEBUG) {
                    Log.d(TAG, "Create new event");
                }
            }
        } else if (icicle != null && icicle.containsKey(BUNDLE_KEY_EVENT_ID)) {
            eventId = icicle.getLong(BUNDLE_KEY_EVENT_ID);
        }

        boolean allDay = intent.getBooleanExtra(EXTRA_EVENT_ALL_DAY, false);

        long begin = intent.getLongExtra(EXTRA_EVENT_BEGIN_TIME, linkedBegin);
        long end = intent.getLongExtra(EXTRA_EVENT_END_TIME, linkedEnd);
        if (begin == 0) begin = linkedBegin;
        if (end == 0) end = linkedEnd;
        if (end != -1) {
            info.endTime = new Time();
            if (allDay) {
                info.endTime.setTimezone(Time.TIMEZONE_UTC);
            }
            info.endTime.set(end);
        }
        if (begin != -1) {
            info.startTime = new Time();
            if (allDay) {
                info.startTime.setTimezone(Time.TIMEZONE_UTC);
            }
            info.startTime.set(begin);
        }
        info.id = eventId;
        info.eventTitle = intent.getStringExtra(Events.TITLE);
        info.calendarId = intent.getLongExtra(Events.CALENDAR_ID, -1);

        if (allDay) {
            info.extraLong = CalendarController.EXTRA_CREATE_ALL_DAY;
        } else {
            info.extraLong = 0;
        }
        return info;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
