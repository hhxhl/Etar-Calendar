package com.android.calendar;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.View;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.android.calendar.timezonepicker.TimeZonePickerDialog;
import java.util.function.Consumer;
import java.util.function.Supplier;
import android.os.Bundle;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import ws.xsoh.etar.R;

/** Shared picker configuration; callers retain their own date/time business logic. */
public final class ItemDateTimePickers {
    private ItemDateTimePickers() {}

    /** Reattach callbacks after recreation and prevent duplicate picker fragments. */
    public static void bindDate(View button, FragmentManager manager, String tag,
            Supplier<Long> selection, Consumer<Long> onSelected) {
        Consumer<MaterialDatePicker<Long>> attach = picker -> {
            picker.clearOnPositiveButtonClickListeners();
            picker.addOnPositiveButtonClickListener(onSelected::accept);
        };
        button.setOnClickListener(v -> {
            if (manager.isStateSaved() || manager.findFragmentByTag(tag) != null) return;
            MaterialDatePicker<Long> picker = date(button.getContext(), selection.get());
            attach.accept(picker);
            picker.showNow(manager, tag);
        });
        Fragment existing = manager.findFragmentByTag(tag);
        if (existing instanceof MaterialDatePicker) {
            @SuppressWarnings("unchecked") MaterialDatePicker<Long> picker = (MaterialDatePicker<Long>) existing;
            attach.accept(picker);
        }
    }

    public static void bindTime(View button, FragmentManager manager, String tag,
            Supplier<java.time.LocalTime> selection, Consumer<java.time.LocalTime> onSelected) {
        Consumer<MaterialTimePicker> attach = picker -> {
            picker.clearOnPositiveButtonClickListeners();
            picker.addOnPositiveButtonClickListener(v ->
                    onSelected.accept(java.time.LocalTime.of(picker.getHour(), picker.getMinute())));
        };
        button.setOnClickListener(v -> {
            if (manager.isStateSaved() || manager.findFragmentByTag(tag) != null) return;
            java.time.LocalTime value = selection.get();
            MaterialTimePicker picker = time(button.getContext(), value.getHour(), value.getMinute());
            attach.accept(picker);
            picker.showNow(manager, tag);
        });
        Fragment existing = manager.findFragmentByTag(tag);
        if (existing instanceof MaterialTimePicker) attach.accept((MaterialTimePicker) existing);
    }

    public static void restoreZone(FragmentManager manager, String tag,
            TimeZonePickerDialog.OnTimeZoneSetListener listener) {
        Fragment existing = manager.findFragmentByTag(tag);
        if (existing instanceof TimeZonePickerDialog)
            ((TimeZonePickerDialog) existing).setOnTimeZoneSetListener(listener);
    }

    public static void showZone(FragmentManager manager, String tag, long millis, String zone,
            TimeZonePickerDialog.OnTimeZoneSetListener listener) {
        if (manager.isStateSaved() || manager.findFragmentByTag(tag) != null) return;
        Bundle args = new Bundle();
        args.putLong(TimeZonePickerDialog.BUNDLE_START_TIME_MILLIS, millis);
        args.putString(TimeZonePickerDialog.BUNDLE_TIME_ZONE, zone);
        TimeZonePickerDialog picker = new TimeZonePickerDialog();
        picker.setArguments(args);
        picker.setOnTimeZoneSetListener(listener);
        picker.showNow(manager, tag);
    }

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
