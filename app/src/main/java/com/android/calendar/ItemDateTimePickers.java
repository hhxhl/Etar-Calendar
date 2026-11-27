package com.android.calendar;

import android.content.Context;
import android.text.format.DateFormat;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import ws.xsoh.etar.R;

/** Shared picker configuration; callers retain their own date/time business logic. */
public final class ItemDateTimePickers {
    private ItemDateTimePickers() {}

    public static MaterialDatePicker<Long> date(Context context, long utcSelection) {
        return MaterialDatePicker.Builder.datePicker()
                .setSelection(utcSelection)
                .setCalendarConstraints(new CalendarConstraints.Builder()
                        .setFirstDayOfWeek(Utils.getFirstDayOfWeekAsCalendar(context))
                        .build())
                .setTitleText(R.string.goto_date)
                .build();
    }

    public static MaterialTimePicker time(Context context, int hour, int minute) {
        return new MaterialTimePicker.Builder()
                .setHour(hour)
                .setMinute(minute)
                .setTimeFormat(DateFormat.is24HourFormat(context)
                        ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .build();
    }
}
