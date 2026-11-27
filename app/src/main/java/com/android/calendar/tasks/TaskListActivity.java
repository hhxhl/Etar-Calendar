package com.android.calendar.tasks;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.net.Uri;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.CompoundButton;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.android.calendar.Utils;
import com.android.calendar.colorpicker.ColorPickerDialog;
import com.android.calendar.timezonepicker.TimeZonePickerDialog;
import com.android.calendar.timezonepicker.TimeZonePickerUtils;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import ws.xsoh.etar.databinding.SimpleFrameLayoutMaterialBinding;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.android.calendar.theme.DynamicThemeKt;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import ws.xsoh.etar.R;

/** Local task and reminder editor. List management lives in Settings. */
public final class TaskListActivity extends AppCompatActivity {
    private TaskStore store;
    private long listId=0;
    private boolean editorOnly, finishAfterPermission, waitingForPermissionSettings;
    private boolean restoredPermissionFlow;
    private Bundle restoredDraft;
    private java.util.function.Supplier<Bundle> captureDraft;
    private Runnable saveEditor, deleteEditor;
    private Bundle initialDraft;

    private void discardOrFinish() {
        if (captureDraft == null || initialDraft == null
                || sameDraft(initialDraft, captureDraft.get())) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.discard_event_changes)
                .setPositiveButton(R.string.discard, (dialog, which) -> finish())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static boolean sameDraft(Bundle first, Bundle second) {
        if (!first.keySet().equals(second.keySet())) return false;
        for (String key : first.keySet()) {
            if (!java.util.Objects.equals(first.get(key), second.get(key))) return false;
        }
        return true;
    }
    private static final String COLOR_PICKER = "TaskItemColorPicker";
    private static final String DATE_PICKER = "TaskDatePicker", TIME_PICKER = "TaskTimePicker", ZONE_PICKER = "TaskZonePicker";
    private static final String[] RULES={null,"FREQ=DAILY","FREQ=WEEKLY","FREQ=MONTHLY","FREQ=YEARLY"};
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String name(TaskStore.TaskList l){return l.name.isEmpty()?getString(R.string.tasks_default_list):l.name;}
    @Override public void onCreate(Bundle state) {
        DynamicThemeKt.applyTheme(this);
        super.onCreate(state);
        store=TaskStore.get(this);
        editorOnly=getIntent().getBooleanExtra("new_task",false)||getIntent().hasExtra("task_id");

        listId=getIntent().getLongExtra("list_id",0);
        if(state!=null){
            listId=state.getLong("list_id");
            finishAfterPermission=state.getBoolean("finish_after_permission");
            waitingForPermissionSettings=state.getBoolean("waiting_for_permission_settings");
            restoredPermissionFlow=finishAfterPermission || waitingForPermissionSettings;
            restoredDraft=state.getBundle("editor_draft");
            initialDraft=state.getBundle("initial_editor_draft");
        }
        if(editorOnly){
            if(finishAfterPermission||waitingForPermissionSettings)return;
            long id=getIntent().getLongExtra("task_id",0);
            TaskStore.Task task=id==0?newTask():store.find(id);
            if(task==null){finish();return;}
            if(id==0){
                boolean reminder = getIntent().getBooleanExtra("new_reminder", false) || getIntent().hasExtra("date");
                long date=getIntent().getLongExtra("date",System.currentTimeMillis());
                if(reminder){
                    task.allDay=!getIntent().getBooleanExtra("timed_task",false);
                    String calendarZone=getIntent().getStringExtra("calendar_zone");
                    ZoneId selectedZone=calendarZone==null?ZoneId.systemDefault():ZoneId.of(calendarZone);
                    task.tz=task.allDay?"UTC":selectedZone.getId();
                    task.due=task.allDay?Instant.ofEpochMilli(date).atZone(selectedZone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli():date;
                    task.anchor=task.due;
                    task.alarm=!task.allDay;
                }
            }
            edit(task);
            return;
        }
        if (getIntent().getBooleanExtra("manage_lists", false)) {
            startActivity(new Intent(this, com.android.calendar.settings.SettingsActivity.class));
        }
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("list_id", listId);
        state.putBoolean("finish_after_permission", finishAfterPermission);
        state.putBoolean("waiting_for_permission_settings", waitingForPermissionSettings);
        if (captureDraft != null) state.putBundle("editor_draft", captureDraft.get());
        if (initialDraft != null) state.putBundle("initial_editor_draft", initialDraft);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForPermissionSettings || restoredPermissionFlow) {
            waitingForPermissionSettings = false;
            restoredPermissionFlow = false;
            TaskAlarmReceiver.schedule(this);
            finishEditorAfterPermission();
        }
    }

    private TaskStore.Task newTask(){TaskStore.Task t=new TaskStore.Task();t.listId=listId==0?1:listId;return t;}
    private ArrayAdapter<String> adapter(List<String> labels){ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return a;}
    private static void hide(View root, int... ids) {
        for (int id : ids) root.findViewById(id).setVisibility(View.GONE);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        if (saveEditor == null) return super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.edit_event_title_bar, menu);
        if (deleteEditor != null) menu.add(0, R.id.info_action_delete, 13, R.string.delete_label);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (saveEditor != null && id == R.id.action_done) { saveEditor.run(); return true; }
        if (saveEditor != null && (id == R.id.action_cancel || id == android.R.id.home)) {
            discardOrFinish(); return true;
        }
        if (deleteEditor != null && id == R.id.info_action_delete) { deleteEditor.run(); return true; }
        return super.onOptionsItemSelected(item);
    }

    /** Reuse the event editor's layout, toolbar, separators and Material date/time controls.
     * Only the task-specific binding and persistence are different. */
    private void edit(TaskStore.Task task) {
        final boolean reminder = task.due != null;
        SimpleFrameLayoutMaterialBinding binding =
                SimpleFrameLayoutMaterialBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.include.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(reminder
                    ? (task.id == 0 ? R.string.reminders_add : R.string.reminders_edit)
                    : (task.id == 0 ? R.string.tasks_add : R.string.tasks_edit));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        binding.bodyFrame.addView(scroll, new ViewGroup.LayoutParams(-1, -1));
        LinearLayout form = (LinearLayout) getLayoutInflater().inflate(R.layout.edit_event_all, scroll, false);
        scroll.addView(form);
        form.setFocusableInTouchMode(true);
        form.requestFocus();

        EditText title = form.findViewById(R.id.title);
        title.setText(task.title);
        EditText notes = form.findViewById(R.id.description);
        notes.setText(task.notes);
        EditText url = form.findViewById(R.id.url);
        url.setText(task.url);
        CompoundButton countdown = form.findViewById(R.id.event_countdown);
        countdown.setChecked(reminder && task.countdown);
        int[] itemColor = {task.color};
        Spinner list = form.findViewById(R.id.calendars_spinner);
        List<TaskStore.TaskList> available = store.lists();
        if (available.isEmpty()) { finish(); return; }
        ArrayList<String> names = new ArrayList<>();
        int listPos = 0;
        for (int i = 0; i < available.size(); i++) {
            names.add(name(available.get(i)));
            if (available.get(i).id == task.listId) listPos = i;
        }
        list.setAdapter(adapter(names));
        list.setSelection(listPos);
        list.setContentDescription(getString(R.string.tasks_lists));
        ((ImageView) form.findViewById(R.id.calendar_selector_group_icon)).setImageResource(R.drawable.ic_task_outline);
        View listColor = form.findViewById(R.id.calendar_selector_group_background);
        Runnable updateColor = () -> {
            int pos = list.getSelectedItemPosition();
            if (pos < 0 || pos >= available.size()) return;
            int color = itemColor[0] != 0 ? itemColor[0] : available.get(pos).color;
            listColor.setBackgroundColor(color == 0 ? getColor(R.color.event_center)
                    : Utils.getDisplayColorFromColor(this, color));
        };
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                updateColor.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        updateColor.run();
        View palette = form.findViewById(R.id.change_color);
        palette.setEnabled(true);
        palette.setContentDescription(getString(R.string.tasks_choose_color));
        com.android.calendar.colorpicker.ColorPickerSwatch.OnColorSelectedListener colorListener = color -> {
            itemColor[0] = color;
            updateColor.run();
        };
        palette.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag(COLOR_PICKER) != null) return;
            int pos = list.getSelectedItemPosition();
            int selected = itemColor[0] != 0 ? itemColor[0] : pos < 0 ? 0 : available.get(pos).color;
            int[] colors = ColorPickerDialog.defaultColors(this, selected);
            TaskColorPicker picker = new TaskColorPicker();
            picker.initialize(R.string.tasks_choose_color, colors, selected, 4,
                    ColorPickerDialog.paletteSize(this));
            picker.setOnColorSelectedListener(colorListener);
            picker.showOnce(getSupportFragmentManager(), COLOR_PICKER);
        });
        androidx.fragment.app.Fragment colorPicker = getSupportFragmentManager().findFragmentByTag(COLOR_PICKER);
        if (colorPicker instanceof TaskColorPicker)
            ((TaskColorPicker) colorPicker).setOnColorSelectedListener(colorListener);

        // The shared layout also contains event-only fields; collapse those sections.
        hide(form, R.id.end_date, R.id.end_time,
                R.id.response_group, R.id.response_row, R.id.organizer_row,
                R.id.add_attendees_group, R.id.attendees_icon, R.id.add_attendees_row,
                R.id.where_icon, R.id.where_row,
                R.id.reminder_add, R.id.view6,
                R.id.visibility_icon, R.id.visibility);
        ConstraintLayout.LayoutParams descriptionParams =
                (ConstraintLayout.LayoutParams) form.findViewById(R.id.description_row).getLayoutParams();
        descriptionParams.topToBottom = reminder ? R.id.view2 : R.id.view1;
        descriptionParams.topMargin = dp(10);
        form.findViewById(R.id.description_row).setLayoutParams(descriptionParams);
        ConstraintLayout.LayoutParams afterAlarm =
                (ConstraintLayout.LayoutParams) form.findViewById(R.id.view5).getLayoutParams();
        afterAlarm.topToBottom = R.id.reminder_items_container;
        form.findViewById(R.id.view5).setLayoutParams(afterAlarm);

        CompoundButton allDay = form.findViewById(R.id.is_all_day);
        allDay.setChecked(reminder && task.allDay);
        String defaultZone = getIntent().getStringExtra("calendar_zone");
        if (defaultZone == null) defaultZone = Utils.getTimeZone(this, null);
        ZoneId[] zone = {ZoneId.of(task.tz == null || task.allDay ? defaultZone : task.tz)};
        ZoneId initialZone = zone[0];
        ZonedDateTime initial = task.due == null ? ZonedDateTime.now(zone[0])
                : Instant.ofEpochMilli(task.due).atZone(task.allDay ? ZoneOffset.UTC : zone[0]);
        LocalDate[] date = {initial.toLocalDate()};
        LocalTime[] time = {initial.toLocalTime().withSecond(0).withNano(0)};
        LocalDate initialDate = date[0];
        LocalTime initialTime = time[0];
        boolean initialAllDay = allDay.isChecked();

        Button dateButton = form.findViewById(R.id.start_date);
        Button timeButton = form.findViewById(R.id.start_time);
        Button zoneButton = form.findViewById(R.id.timezone_button);
        View zoneRow = form.findViewById(R.id.timezone_button_row);
        TimeZonePickerUtils zoneLabels = new TimeZonePickerUtils(this);
        CompoundButton alarm = new com.google.android.material.materialswitch.MaterialSwitch(this);
        alarm.setText(R.string.tasks_notify);
        alarm.setChecked(task.alarm);
        ((LinearLayout) form.findViewById(R.id.reminder_items_container)).addView(alarm,
                new LinearLayout.LayoutParams(-1, -2));

        Button repeat = form.findViewById(R.id.rrule);
        String[] repeatOptions = getResources().getStringArray(R.array.tasks_repeat_options);
        int[] repeatIndex = {0};
        for (int i = 0; i < RULES.length; i++)
            if (java.util.Objects.equals(task.rule, RULES[i])) repeatIndex[0] = i;
        int initialRepeat = repeatIndex[0];
        repeat.setText(repeatOptions[repeatIndex[0]]);
        repeat.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tasks_repeat)
                .setSingleChoiceItems(repeatOptions, repeatIndex[0], (dialog, which) -> {
                    repeatIndex[0] = which;
                    repeat.setText(repeatOptions[which]);
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show());

        // Reuse the existing spinner slot, but bind the task priority rather than event availability.
        Spinner priority = form.findViewById(R.id.availability);
        ArrayList<String> priorityNames = new ArrayList<>();
        for (String option : getResources().getStringArray(R.array.tasks_priority_options))
            priorityNames.add(getString(R.string.tasks_priority) + " · " + option);
        priority.setAdapter(adapter(priorityNames));
        priority.setSelection(task.priority == 1 ? 1 : task.priority == 5 ? 2 : task.priority == 9 ? 3 : 0);
        priority.setContentDescription(getString(R.string.tasks_priority));
        ((ImageView) form.findViewById(R.id.availability_icon)).setImageResource(R.drawable.ic_task_outline);

        CheckBox completed = reminder ? ReminderCompletion.create(this) : new com.google.android.material.checkbox.MaterialCheckBox(this);
        completed.setText(R.string.tasks_complete);
        completed.setChecked(task.done);
        if (task.id != 0) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(dp(48), dp(8), dp(24), dp(16));
            form.addView(completed, params);
        }

        Runnable update = () -> {
            long dateMillis = date[0].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            dateButton.setText(android.text.format.DateUtils.formatDateRange(this, new java.util.Formatter(),
                    dateMillis, dateMillis, android.text.format.DateUtils.FORMAT_SHOW_DATE
                            | android.text.format.DateUtils.FORMAT_SHOW_YEAR
                            | android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY, "UTC").toString());
            String pattern = android.text.format.DateFormat.getBestDateTimePattern(
                    java.util.Locale.getDefault(),
                    android.text.format.DateFormat.is24HourFormat(this) ? "Hm" : "hm");
            timeButton.setText(time[0].format(DateTimeFormatter.ofPattern(pattern)));
            long localMillis = date[0].atTime(time[0]).atZone(zone[0]).toInstant().toEpochMilli();
            zoneButton.setText(zoneLabels.getGmtDisplayName(this, zone[0].getId(), localMillis, true));
            timeButton.setVisibility(reminder && !allDay.isChecked() ? View.VISIBLE : View.GONE);
            zoneRow.setVisibility(reminder && !allDay.isChecked() ? View.VISIBLE : View.GONE);
            alarm.setEnabled(reminder && !allDay.isChecked());
        };
        allDay.setOnCheckedChangeListener((v, checked) -> update.run());
        if (!reminder) hide(form, R.id.imageView, R.id.is_all_day_label, R.id.is_all_day,
                R.id.start_date, R.id.start_time, R.id.timezone_button_row, R.id.timezone_icon,
                R.id.timezone_button, R.id.view2, R.id.rrule_icon, R.id.rrule,
                R.id.event_countdown, R.id.view4, R.id.reminder_icon, R.id.reminder_items_container, R.id.view5);

        captureDraft = () -> {
            Bundle draft = new Bundle();
            draft.putString("title", title.getText().toString());
            draft.putString("notes", notes.getText().toString());
            draft.putString("url", url.getText().toString());
            draft.putInt("item_color", itemColor[0]);
            draft.putBoolean("countdown", countdown.isChecked());
            draft.putBoolean("complete", completed.isChecked());
            int pos = list.getSelectedItemPosition();
            draft.putLong("list_id", pos < 0 ? task.listId : available.get(pos).id);
            draft.putBoolean("has_time", reminder && !allDay.isChecked());
            draft.putLong("date", date[0].toEpochDay());
            draft.putLong("time", time[0].toSecondOfDay());
            draft.putString("zone", zone[0].getId());
            draft.putBoolean("alarm", alarm.isChecked());
            draft.putInt("repeat", repeatIndex[0]);
            draft.putInt("priority", priority.getSelectedItemPosition());
            return draft;
        };
        if (initialDraft == null) initialDraft = captureDraft.get();

        // Restore only the draft, not the original baseline used for detecting schedule edits.
        if (restoredDraft != null) {
            Bundle draft = restoredDraft;
            restoredDraft = null;
            title.setText(draft.getString("title", task.title));
            notes.setText(draft.getString("notes", task.notes));
            url.setText(draft.getString("url", task.url));
            itemColor[0] = draft.getInt("item_color", task.color);
            countdown.setChecked(reminder && draft.getBoolean("countdown", task.countdown));
            completed.setChecked(draft.getBoolean("complete", task.done));
            long selectedList = draft.getLong("list_id", task.listId);
            for (int i = 0; i < available.size(); i++)
                if (available.get(i).id == selectedList) list.setSelection(i);
            allDay.setChecked(reminder && !draft.getBoolean("has_time", !initialAllDay));
            date[0] = LocalDate.ofEpochDay(draft.getLong("date", initialDate.toEpochDay()));
            time[0] = LocalTime.ofSecondOfDay(draft.getLong("time", initialTime.toSecondOfDay()));
            zone[0] = ZoneId.of(draft.getString("zone", initialZone.getId()));
            alarm.setChecked(draft.getBoolean("alarm", task.alarm));
            repeatIndex[0] = draft.getInt("repeat", repeatIndex[0]);
            repeat.setText(repeatOptions[repeatIndex[0]]);
            priority.setSelection(draft.getInt("priority", priority.getSelectedItemPosition()));
        }
        update.run();
        updateColor.run();

        // The Material picker fragments survive Activity recreation; rebind their listeners.
        java.util.function.Consumer<MaterialDatePicker<Long>> bindDate = picker -> {
            picker.clearOnPositiveButtonClickListeners();
            picker.addOnPositiveButtonClickListener(selection -> {
                date[0] = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate();
                update.run();
            });
        };
        dateButton.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag(DATE_PICKER) != null) return;
            MaterialDatePicker<Long> picker = com.android.calendar.ItemDateTimePickers.date(this,
                    date[0].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
            bindDate.accept(picker);
            picker.show(getSupportFragmentManager(), DATE_PICKER);
        });
        androidx.fragment.app.Fragment datePicker = getSupportFragmentManager().findFragmentByTag(DATE_PICKER);
        if (datePicker instanceof MaterialDatePicker) {
            @SuppressWarnings("unchecked") MaterialDatePicker<Long> restored = (MaterialDatePicker<Long>) datePicker;
            bindDate.accept(restored);
        }

        java.util.function.Consumer<MaterialTimePicker> bindTime = picker -> {
            picker.clearOnPositiveButtonClickListeners();
            picker.addOnPositiveButtonClickListener(v -> {
                time[0] = LocalTime.of(picker.getHour(), picker.getMinute());
                update.run();
            });
        };
        timeButton.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag(TIME_PICKER) != null) return;
            MaterialTimePicker picker = com.android.calendar.ItemDateTimePickers.time(
                    this, time[0].getHour(), time[0].getMinute());
            bindTime.accept(picker);
            picker.show(getSupportFragmentManager(), TIME_PICKER);
        });
        androidx.fragment.app.Fragment timePicker = getSupportFragmentManager().findFragmentByTag(TIME_PICKER);
        if (timePicker instanceof MaterialTimePicker) bindTime.accept((MaterialTimePicker) timePicker);

        TimeZonePickerDialog.OnTimeZoneSetListener zoneListener = tzi -> {
            zone[0] = ZoneId.of(tzi.mTzId);
            update.run();
        };
        zoneButton.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag(ZONE_PICKER) != null) return;
            Bundle args = new Bundle();
            args.putLong(TimeZonePickerDialog.BUNDLE_START_TIME_MILLIS,
                    date[0].atTime(time[0]).atZone(zone[0]).toInstant().toEpochMilli());
            args.putString(TimeZonePickerDialog.BUNDLE_TIME_ZONE, zone[0].getId());
            TimeZonePickerDialog picker = new TimeZonePickerDialog();
            picker.setArguments(args);
            picker.setOnTimeZoneSetListener(zoneListener);
            picker.show(getSupportFragmentManager(), ZONE_PICKER);
        });
        androidx.fragment.app.Fragment zonePicker = getSupportFragmentManager().findFragmentByTag(ZONE_PICKER);
        if (zonePicker instanceof TimeZonePickerDialog)
            ((TimeZonePickerDialog) zonePicker).setOnTimeZoneSetListener(zoneListener);

        if (task.id != 0) deleteEditor = () -> new MaterialAlertDialogBuilder(this)
                .setMessage(reminder ? R.string.reminders_delete_confirm : R.string.tasks_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.tasks_delete, (dialog, which) -> {
                    store.delete(task.id);
                    captureDraft = null;
                    finish();
                }).show();

        saveEditor = () -> {
            String text = title.getText().toString().trim();
            if (text.isEmpty()) { title.setError(getString(R.string.tasks_required)); title.requestFocus(); return; }
            int pos = list.getSelectedItemPosition();
            if (pos < 0 || pos >= available.size()) return;
            task.title = text;
            task.notes = notes.getText().toString();
            task.url = url.getText().toString().trim();
            task.color = itemColor[0];
            task.countdown = reminder && countdown.isChecked();
            task.listId = available.get(pos).id;
            Long oldDue = task.due;
            String oldRule = task.rule;
            boolean scheduleEdited = reminder && (initialAllDay != allDay.isChecked()
                    || !initialDate.equals(date[0])
                    || (!allDay.isChecked() && (!initialTime.equals(time[0]) || !initialZone.equals(zone[0]))));
            task.allDay = reminder && allDay.isChecked();
            if (reminder && (scheduleEdited || task.id == 0)) {
                task.tz = task.allDay ? "UTC" : zone[0].getId();
                task.due = task.allDay ? date[0].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        : date[0].atTime(time[0]).atZone(zone[0]).toInstant().toEpochMilli();
            }
            task.rule = reminder ? (repeatIndex[0] == initialRepeat ? oldRule : RULES[repeatIndex[0]]) : null;
            if (scheduleEdited || !java.util.Objects.equals(oldDue, task.due)
                    || !java.util.Objects.equals(oldRule, task.rule) || task.anchor == null) {
                task.anchor = task.due;
                task.repeatMinute = task.due == null || task.allDay ? null
                        : time[0].getHour() * 60 + time[0].getMinute();
            }
            task.priority = new int[]{0,1,5,9}[Math.max(0, priority.getSelectedItemPosition())];
            task.alarm = reminder && !task.allDay && alarm.isChecked();
            if (!reminder) {
                task.due = null; task.anchor = null; task.repeatMinute = null;
                task.tz = null; task.allDay = false; task.rule = null;
            }
            long saved = store.save(task, completed.isChecked());
            finishAfterPermission = saved > 0 && editorOnly && task.alarm && !task.done;
            captureDraft = null;
            saveEditor = null;
            deleteEditor = null;
            if (finishAfterPermission) checkAlarmPermissions(); else finish();
        };
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { discardOrFinish(); }
        });
        invalidateOptionsMenu();
    }

    /** Same color palette as calendar settings, with an explicit inherit action. */
    public static final class TaskColorPicker extends ColorPickerDialog {
        public TaskColorPicker() {}
        @Override public android.app.Dialog onCreateDialog(Bundle state) {
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) super.onCreateDialog(state);
            dialog.setButton(android.content.DialogInterface.BUTTON_NEUTRAL,
                    getString(R.string.tasks_default_list_color), (d, which) -> onColorSelected(0));
            return dialog;
        }
    }

    private void checkAlarmPermissions(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);return;}
        if(!((AlarmManager)getSystemService(ALARM_SERVICE)).canScheduleExactAlarms()){
            AlertDialog permissionDialog=new MaterialAlertDialogBuilder(this).setMessage(R.string.tasks_exact_alarm_message)
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.tasks_settings,(d,w)->{
                    try{
                        waitingForPermissionSettings=editorOnly;
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));
                    }catch(android.content.ActivityNotFoundException ignored){waitingForPermissionSettings=false;}
                }).create();
            permissionDialog.setOnDismissListener(d->{if(!waitingForPermissionSettings)finishEditorAfterPermission();});
            permissionDialog.show();
        }else finishEditorAfterPermission();
    }
    private void finishEditorAfterPermission(){
        if(editorOnly){finishAfterPermission=false;finish();}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        TaskAlarmReceiver.schedule(this);
        if(code==42){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)checkAlarmPermissions();else finishEditorAfterPermission();}
    }
}
