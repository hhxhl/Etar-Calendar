/*
 * Copyright (C) 2013 The CyanogenMod Project
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

package com.android.calendar.alerts;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.android.calendar.Utils;
import com.android.calendar.settings.GeneralPreferences;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import ws.xsoh.etar.R;

public class SnoozeDelayActivity extends AppCompatActivity {
    private static final String PICKER_TAG = "snooze_delay";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MaterialTimePicker picker = (MaterialTimePicker) getSupportFragmentManager()
                .findFragmentByTag(PICKER_TAG);
        boolean isNew = picker == null;
        if (isNew) {
            int delayMinutes = (int) (Utils.getDefaultSnoozeDelayMs(this) / (60L * 1000L));
            if (delayMinutes < 0) {
                delayMinutes = GeneralPreferences.SNOOZE_DELAY_DEFAULT_TIME;
            }
            // The original picker represents a duration of less than 24 hours.
            delayMinutes = Math.min(delayMinutes, 23 * 60 + 59);
            picker = new MaterialTimePicker.Builder()
                    .setTitleText(R.string.snooze_delay_dialog_title)
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
                    .setHour(delayMinutes / 60)
                    .setMinute(delayMinutes % 60)
                    .build();
        }
        MaterialTimePicker delayPicker = picker;
        picker.addOnPositiveButtonClickListener(view ->
                snooze(delayPicker.getHour(), delayPicker.getMinute()));
        picker.addOnDismissListener(dialog -> {
            if (!isChangingConfigurations()) {
                finish();
            }
        });
        if (isNew) {
            picker.show(getSupportFragmentManager(), PICKER_TAG);
        }
    }

    private void snooze(int hour, int minute) {
        long delay = (hour * 60 + minute) * 60L * 1000L;
        Intent intent = getIntent();
        intent.setClass(this, SnoozeAlarmsService.class);
        intent.putExtra(AlertUtils.SNOOZE_DELAY_KEY, delay);
        startService(intent);
        finish();
    }
}
