package com.android.calendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract.Calendars;
import com.android.calendar.icalendar.IcalImporter;
import java.util.ArrayList;
import java.util.List;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.android.calendar.icalendar.IcalendarUtils;
import com.android.calendar.icalendar.VCalendar;
import com.android.calendar.icalendar.VEvent;

import ws.xsoh.etar.R;

public class ImportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isValidIntent()) {
            Toast.makeText(this, R.string.cal_nothing_to_import, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            checkPermissions();
        }
    }

    private void checkPermissions() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, 1);
        } else parseCalFile();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1 && results.length == 2
                && results[0] == PackageManager.PERMISSION_GRANTED && results[1] == PackageManager.PERMISSION_GRANTED) {
            parseCalFile();
        } else showErrorToast();
    }

    private void showErrorToast() {
        Toast.makeText(this, R.string.cal_import_error_msg, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void parseCalFile() {
        new Thread(() -> {
            try {
                VCalendar calendar = IcalendarUtils.readCalendarFromFile(this, getIntent().getData());
                if (calendar == null || calendar.getAllEvents().isEmpty()
                        || "CANCEL".equalsIgnoreCase(calendar.getProperty(VCalendar.METHOD))) throw new IllegalArgumentException();
                List<IcalImporter.Parsed> parsed = new ArrayList<>();
                int invalid = 0, limited = 0;
                for (VEvent event : calendar.getAllEvents()) {
                    try {
                        IcalImporter.Parsed data = IcalImporter.parse(event);
                        parsed.add(data);
                        if (data.limited) limited++;
                    } catch (RuntimeException e) {
                        invalid++;
                    }
                }
                int skipped = invalid, warnings = limited;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    // Refuse the entire file if events cannot be represented safely, particularly recurrence exceptions.
                    if (skipped != 0) {
                        new AlertDialog.Builder(this).setMessage(getString(R.string.ics_import_unsupported, skipped))
                                .setPositiveButton(android.R.string.ok, (d,w) -> finish())
                                .setOnCancelListener(d -> finish()).show();
                    } else chooseCalendar(parsed, warnings);
                });
            } catch (Exception e) {
                runOnUiThread(this::showErrorToast);
            }
        }, "ics-parse").start();
    }

    private void chooseCalendar(List<IcalImporter.Parsed> events, int warnings) {
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (Cursor c = getContentResolver().query(Calendars.CONTENT_URI,
                new String[]{Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME},
                Calendars.CALENDAR_ACCESS_LEVEL + ">=? AND " + Calendars.VISIBLE + "=1",
                new String[]{Integer.toString(Calendars.CAL_ACCESS_CONTRIBUTOR)}, Calendars.CALENDAR_DISPLAY_NAME)) {
            if (c != null) while (c.moveToNext()) {
                ids.add(c.getLong(0));
                names.add(c.getString(1) + " (" + c.getString(2) + ")");
            }
        } catch (RuntimeException e) { showErrorToast(); return; }
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.ics_import_no_calendar, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.ics_import_choose_calendar)
                .setItems(names.toArray(new String[0]), (d, i) -> {
                    String message = getString(R.string.ics_import_confirm, events.size(), names.get(i));
                    if (warnings > 0) message += "\n\n" + getString(R.string.ics_import_limited, warnings);
                    new AlertDialog.Builder(this).setMessage(message)
                            .setPositiveButton(R.string.ics_import_action, (dialog, which) -> importEvents(ids.get(i), events))
                            .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                            .setOnCancelListener(dialog -> finish()).show();
                }).setNegativeButton(android.R.string.cancel, (d,w) -> finish())
                .setOnCancelListener(d -> finish()).show();
    }

    private void importEvents(long calendarId, List<IcalImporter.Parsed> events) {
        new Thread(() -> {
            try {
                IcalImporter.Result r = IcalImporter.insert(getContentResolver(), calendarId, events);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this).setMessage(getString(R.string.ics_import_result,
                                    r.inserted, r.duplicates, r.failed))
                            .setPositiveButton(android.R.string.ok, (d,w) -> finish())
                            .setOnCancelListener(d -> finish()).show();
                });
            } catch (Exception e) { runOnUiThread(this::showErrorToast); }
        }, "ics-import").start();
    }

    private boolean isValidIntent() {
        Intent intent = getIntent();
        if (intent == null) {
            return false;
        }
        Uri fileUri = intent.getData();
        if (fileUri == null) {
            return false;
        }
        String scheme = fileUri.getScheme();
        return ContentResolver.SCHEME_CONTENT.equals(scheme)
                || ContentResolver.SCHEME_FILE.equals(scheme);
    }

}
