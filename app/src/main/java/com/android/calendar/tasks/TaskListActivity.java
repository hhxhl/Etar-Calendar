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
        com.android.calendar.ItemEditorUi.confirmDiscard(this, this::finish);
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
                    task.tz=selectedZone.getId();
                    task.due=task.allDay?Instant.ofEpochMilli(date).atZone(selectedZone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli():date;
                    task.anchor=task.due;
                    task.alarm=false;
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
        SimpleFrameLayoutMaterialBinding binding =
                SimpleFrameLayoutMaterialBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.include.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(task.id == 0 ? R.string.reminders_add : R.string.reminders_edit);
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
        countdown.setChecked(task.due != null && task.countdown);
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
                    : color);
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

        // Keep the common title, list, color, notes, URL and recurrence layout.
        hide(form, R.id.imageView, R.id.is_all_day_label, R.id.is_all_day,
                R.id.start_date, R.id.start_time, R.id.end_date, R.id.end_time,
                R.id.timezone_button_row, R.id.timezone_icon, R.id.timezone_button,
                R.id.response_group, R.id.response_row, R.id.organizer_row, R.id.view7, R.id.view2,
                R.id.add_attendees_group, R.id.attendees_icon, R.id.add_attendees_row,
                R.id.where_icon, R.id.where_row, R.id.reminder_icon, R.id.reminder_items_container,
                R.id.reminder_add, R.id.view4, R.id.view5, R.id.view6,
                R.id.visibility_icon, R.id.visibility, R.id.availability_icon, R.id.availability);
        ConstraintLayout.LayoutParams descriptionParams =
                (ConstraintLayout.LayoutParams) form.findViewById(R.id.description_row).getLayoutParams();
        descriptionParams.topToBottom = R.id.view1;
        descriptionParams.topMargin = dp(10);
        form.findViewById(R.id.description_row).setLayoutParams(descriptionParams);

        String defaultZone = getIntent().getStringExtra("calendar_zone");
        if (defaultZone == null) defaultZone = Utils.getTimeZone(this, null);
        ZoneId[] zone = {ZoneId.of(task.tz == null || (task.allDay && "UTC".equals(task.tz))
                ? defaultZone : task.tz)};
        ConstraintLayout editor = form.findViewById(R.id.edit_event_all);
        LinearLayout times = new LinearLayout(this);
        times.setId(View.generateViewId());
        times.setOrientation(LinearLayout.VERTICAL);
        ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(0, -2);
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        params.topToBottom = R.id.view3;
        params.setMargins(dp(48), dp(8), dp(24), 0);
        editor.addView(times, params);
        View repeatIcon = form.findViewById(R.id.rrule_icon);
        ConstraintLayout.LayoutParams repeatParams =
                (ConstraintLayout.LayoutParams) repeatIcon.getLayoutParams();
        repeatParams.topToBottom = times.getId();
        repeatIcon.setLayoutParams(repeatParams);

        TimeField start = new TimeField(times, R.string.reminders_start_time, "start",
                task.start, task.startAllDay, true, zone);
        TimeField due = new TimeField(times, R.string.reminders_due_time, "due",
                task.due, task.allDay, true, zone);
        CompoundButton alarm = new com.google.android.material.materialswitch.MaterialSwitch(this);
        alarm.setText(R.string.reminders_alarm_time);
        alarm.setChecked(task.alarm);
        times.addView(alarm, new LinearLayout.LayoutParams(-1, -2));
        Button alarmMode = outlinedButton();
        times.addView(alarmMode, new LinearLayout.LayoutParams(-1, -2));
        int[] mode = {task.alarm ? task.alarmMode : TaskStore.Task.ALARM_ABSOLUTE};
        int[] offset = {task.alarmOffset};
        TimeField absolute = new TimeField(times, R.string.reminders_alarm_absolute, "alarm",
                task.alarmAbsolute, false, false, zone);
        absolute.enabled.setChecked(true);
        absolute.enabled.setVisibility(View.GONE);
        Button offsetButton = outlinedButton();
        times.addView(offsetButton, new LinearLayout.LayoutParams(-1, -2));
        Button zoneButton = outlinedButton();
        times.addView(zoneButton, new LinearLayout.LayoutParams(-1, -2));
        TimeZonePickerUtils zoneLabels = new TimeZonePickerUtils(this);
        int[] modeLabels = {R.string.reminders_alarm_absolute, R.string.reminders_alarm_start,
                R.string.reminders_alarm_due};

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

        // Preserve the stored priority; it is not part of the simplified editor.
        CheckBox completed = ReminderCompletion.create(this);
        completed.setText(R.string.tasks_complete);
        completed.setChecked(task.done);
        if (task.id != 0) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(dp(48), dp(8), dp(24), dp(16));
            form.addView(completed, params);
        }

        Runnable update = () -> {
            start.refresh();
            due.refresh();
            boolean hasAlarm = alarm.isChecked();
            alarmMode.setVisibility(hasAlarm ? View.VISIBLE : View.GONE);
            alarmMode.setText(modeLabels[mode[0]]);
            absolute.root.setVisibility(hasAlarm && mode[0] == TaskStore.Task.ALARM_ABSOLUTE
                    ? View.VISIBLE : View.GONE);
            absolute.refresh();
            offsetButton.setVisibility(hasAlarm && mode[0] != TaskStore.Task.ALARM_ABSOLUTE
                    ? View.VISIBLE : View.GONE);
            offsetButton.setText(offset[0] == 0 ? getString(R.string.reminders_alarm_on_time)
                    : getString(R.string.reminders_alarm_before_minutes, offset[0]));
            boolean any = start.enabled.isChecked() || due.enabled.isChecked() || hasAlarm;
            zoneButton.setVisibility(any ? View.VISIBLE : View.GONE);
            zoneButton.setText(zoneLabels.getGmtDisplayName(this, zone[0].getId(),
                    System.currentTimeMillis(), true));
            repeat.setEnabled(any);
            countdown.setVisibility(due.enabled.isChecked() ? View.VISIBLE : View.GONE);
        };
        start.changed = update;
        due.changed = update;
        absolute.changed = update;
        alarm.setOnCheckedChangeListener((v, checked) -> update.run());
        alarmMode.setOnClickListener(v -> {
            ArrayList<String> labels = new ArrayList<>();
            ArrayList<Integer> modes = new ArrayList<>();
            for (int i = 0; i < modeLabels.length; i++) {
                if (i == TaskStore.Task.ALARM_START && !start.enabled.isChecked()) continue;
                if (i == TaskStore.Task.ALARM_DUE && !due.enabled.isChecked()) continue;
                modes.add(i);
                labels.add(getString(modeLabels[i]));
            }
            new MaterialAlertDialogBuilder(this).setTitle(R.string.reminders_alarm_time)
                    .setSingleChoiceItems(labels.toArray(new String[0]), modes.indexOf(mode[0]), (d, which) -> {
                        mode[0] = modes.get(which);
                        update.run();
                        d.dismiss();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        offsetButton.setOnClickListener(v -> {
            int[] choices = {0,5,15,30,60,1440,10080};
            String[] labels = {getString(R.string.reminders_alarm_on_time),
                    getString(R.string.reminders_alarm_before_minutes,5),
                    getString(R.string.reminders_alarm_before_minutes,15),
                    getString(R.string.reminders_alarm_before_minutes,30),
                    getString(R.string.reminders_alarm_before_hour),
                    getString(R.string.reminders_alarm_before_day),
                    getString(R.string.reminders_alarm_before_week),
                    getString(R.string.reminders_alarm_custom)};
            int selected = choices.length;
            for (int i=0;i<choices.length;i++) if (choices[i]==offset[0]) selected=i;
            new MaterialAlertDialogBuilder(this).setTitle(R.string.reminders_alarm_time)
                    .setSingleChoiceItems(labels, selected, (d, which) -> {
                        d.dismiss();
                        if (which < choices.length) {
                            offset[0] = choices[which]; update.run(); return;
                        }
                        EditText input = new EditText(this);
                        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                        input.setText(Integer.toString(offset[0]));
                        input.setHint(R.string.reminders_alarm_custom);
                        AlertDialog custom = new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.reminders_alarm_custom).setView(input)
                                .setNegativeButton(android.R.string.cancel,null)
                                .setPositiveButton(android.R.string.ok,null).create();
                        custom.setOnShowListener(ignored -> custom.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(button -> {
                                    try {
                                        int minutes = Integer.parseInt(input.getText().toString());
                                        if (minutes < 0) throw new NumberFormatException();
                                        offset[0]=minutes; update.run(); custom.dismiss();
                                    } catch (NumberFormatException error) {
                                        input.setError(getString(R.string.tasks_required));
                                    }
                                }));
                        custom.show();
                    }).setNegativeButton(android.R.string.cancel,null).show();
        });

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
            start.capture(draft);
            due.capture(draft);
            absolute.capture(draft);
            draft.putString("zone", zone[0].getId());
            draft.putBoolean("alarm", alarm.isChecked());
            draft.putInt("alarm_mode", mode[0]);
            draft.putInt("alarm_offset", offset[0]);
            draft.putInt("repeat", repeatIndex[0]);
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
            countdown.setChecked(draft.getBoolean("countdown", task.countdown));
            completed.setChecked(draft.getBoolean("complete", task.done));
            long selectedList = draft.getLong("list_id", task.listId);
            for (int i = 0; i < available.size(); i++)
                if (available.get(i).id == selectedList) list.setSelection(i);
            start.restore(draft);
            due.restore(draft);
            absolute.restore(draft);
            zone[0] = ZoneId.of(draft.getString("zone", zone[0].getId()));
            alarm.setChecked(draft.getBoolean("alarm", task.alarm));
            mode[0] = draft.getInt("alarm_mode", mode[0]);
            offset[0] = draft.getInt("alarm_offset", offset[0]);
            repeatIndex[0] = draft.getInt("repeat", repeatIndex[0]);
            repeat.setText(repeatOptions[repeatIndex[0]]);
        }
        update.run();
        updateColor.run();

        TimeZonePickerDialog.OnTimeZoneSetListener zoneListener = tzi -> {
            zone[0] = ZoneId.of(tzi.mTzId);
            update.run();
        };
        zoneButton.setOnClickListener(v -> com.android.calendar.ItemDateTimePickers.showZone(
                getSupportFragmentManager(), ZONE_PICKER, System.currentTimeMillis(),
                zone[0].getId(), zoneListener));
        com.android.calendar.ItemDateTimePickers.restoreZone(getSupportFragmentManager(), ZONE_PICKER, zoneListener);

        if (task.id != 0) deleteEditor = () -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.reminders_delete_confirm)
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
            Long newStart = start.value(), newDue = due.value();
            if (newStart != null && newDue != null) {
                long from = start.comparisonValue(false), until = due.comparisonValue(true);
                if (from > until) {
                    android.widget.Toast.makeText(this, R.string.reminders_invalid_range,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (alarm.isChecked() && ((mode[0] == TaskStore.Task.ALARM_START && newStart == null)
                    || (mode[0] == TaskStore.Task.ALARM_DUE && newDue == null))) {
                android.widget.Toast.makeText(this, R.string.reminders_alarm_missing_date,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            Long oldDisplay = task.displayTime();
            boolean oldAllDay = task.displayAllDay();
            String oldZone = task.tz, oldRule = task.rule;
            task.start = newStart;
            task.startAllDay = newStart != null && start.allDay.isChecked();
            task.due = newDue;
            task.allDay = newDue != null && due.allDay.isChecked();
            task.tz = zone[0].getId();
            task.alarm = alarm.isChecked();
            task.alarmMode = mode[0];
            task.alarmOffset = offset[0];
            task.alarmAbsolute = task.alarm && mode[0] == TaskStore.Task.ALARM_ABSOLUTE
                    ? absolute.value() : null;
            task.countdown = newDue != null && countdown.isChecked();
            task.listId = available.get(pos).id;
            Long display = task.displayTime();
            task.rule = display == null ? null : (repeatIndex[0] == initialRepeat ? oldRule : RULES[repeatIndex[0]]);
            if (!java.util.Objects.equals(oldDisplay, display) || oldAllDay != task.displayAllDay()
                    || !java.util.Objects.equals(oldZone, task.tz)
                    || !java.util.Objects.equals(oldRule, task.rule) || task.anchor == null) {
                task.anchor = display;
                if (display == null || task.displayAllDay()) task.repeatMinute = null;
                else {
                    LocalTime wall = Instant.ofEpochMilli(display).atZone(zone[0]).toLocalTime();
                    task.repeatMinute = wall.getHour() * 60 + wall.getMinute();
                }
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

    private Button outlinedButton() {
        return new com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
    }

    /** Optional date/time row, backed by the same date/time pickers as the event editor. */
    private final class TimeField {
        final LinearLayout root, controls;
        final CompoundButton enabled, allDay;
        final Button dateButton, timeButton;
        final String key;
        final ZoneId[] zone;
        final Long original;
        final LocalDate originalDate;
        final LocalTime originalTime;
        final boolean originalAllDay;
        final ZoneId originalZone;
        LocalDate date;
        LocalTime time;
        Runnable changed = () -> {};

        TimeField(LinearLayout parent, int label, String key, Long value, boolean dateOnly,
                boolean allowAllDay, ZoneId[] zone) {
            this.key=key; this.zone=zone; original=value; originalZone=zone[0]; originalAllDay=dateOnly;
            ZonedDateTime initial = value == null ? ZonedDateTime.now(zone[0]).plusMinutes(5)
                    : Instant.ofEpochMilli(value).atZone(dateOnly ? ZoneOffset.UTC : zone[0]);
            originalDate=date=initial.toLocalDate();
            originalTime=time=initial.toLocalTime().withSecond(0).withNano(0);
            root=new LinearLayout(TaskListActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            parent.addView(root,new LinearLayout.LayoutParams(-1,-2));
            enabled=new com.google.android.material.materialswitch.MaterialSwitch(TaskListActivity.this);
            enabled.setText(label); enabled.setChecked(value!=null);
            root.addView(enabled,new LinearLayout.LayoutParams(-1,-2));
            controls=new LinearLayout(TaskListActivity.this);
            controls.setOrientation(LinearLayout.VERTICAL);
            root.addView(controls,new LinearLayout.LayoutParams(-1,-2));
            allDay=new com.google.android.material.materialswitch.MaterialSwitch(TaskListActivity.this);
            allDay.setText(R.string.edit_event_all_day_label);
            allDay.setChecked(allowAllDay && dateOnly);
            controls.addView(allDay,new LinearLayout.LayoutParams(-1,-2));
            allDay.setVisibility(allowAllDay ? View.VISIBLE : View.GONE);
            LinearLayout buttons=new LinearLayout(TaskListActivity.this);
            controls.addView(buttons,new LinearLayout.LayoutParams(-1,-2));
            dateButton=outlinedButton(); timeButton=outlinedButton();
            buttons.addView(dateButton,new LinearLayout.LayoutParams(0,-2,1));
            buttons.addView(timeButton,new LinearLayout.LayoutParams(-2,-2));
            enabled.setOnCheckedChangeListener((v,checked)->changed.run());
            allDay.setOnCheckedChangeListener((v,checked)->changed.run());
            com.android.calendar.ItemDateTimePickers.bindDate(dateButton,getSupportFragmentManager(),
                    DATE_PICKER+key,()->date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),valueUtc->{
                        date=Instant.ofEpochMilli(valueUtc).atZone(ZoneOffset.UTC).toLocalDate(); changed.run();
                    });
            com.android.calendar.ItemDateTimePickers.bindTime(timeButton,getSupportFragmentManager(),
                    TIME_PICKER+key,()->time,selected->{time=selected; changed.run();});
            refresh();
        }
        void refresh() {
            controls.setVisibility(enabled.isChecked()?View.VISIBLE:View.GONE);
            timeButton.setVisibility(allDay.isChecked()?View.GONE:View.VISIBLE);
            dateButton.setText(com.android.calendar.ItemEditorUi.formatDateTime(TaskListActivity.this,
                    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_YEAR
                    | android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY,"UTC"));
            String pattern=android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(),
                    android.text.format.DateFormat.is24HourFormat(TaskListActivity.this)?"Hm":"hm");
            timeButton.setText(time.format(DateTimeFormatter.ofPattern(pattern)));
        }
        Long value() {
            if (!enabled.isChecked()) return null;
            boolean dateOnly=allDay.isChecked();
            if (original != null && originalAllDay==dateOnly && originalDate.equals(date)
                    && (dateOnly || (originalTime.equals(time) && originalZone.equals(zone[0])))) return original;
            return dateOnly ? date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    : date.atTime(time).atZone(zone[0]).toInstant().toEpochMilli();
        }
        long comparisonValue(boolean end) {
            if (!allDay.isChecked()) return value();
            return (end ? date.plusDays(1) : date).atStartOfDay(zone[0]).toInstant().toEpochMilli()
                    - (end ? 1 : 0);
        }
        void capture(Bundle b) {
            b.putBoolean(key+"_enabled",enabled.isChecked());
            b.putBoolean(key+"_all_day",allDay.isChecked());
            b.putLong(key+"_date",date.toEpochDay());
            b.putLong(key+"_time",time.toSecondOfDay());
        }
        void restore(Bundle b) {
            date=LocalDate.ofEpochDay(b.getLong(key+"_date",date.toEpochDay()));
            time=LocalTime.ofSecondOfDay(b.getLong(key+"_time",time.toSecondOfDay()));
            enabled.setChecked(b.getBoolean(key+"_enabled",enabled.isChecked()));
            allDay.setChecked(b.getBoolean(key+"_all_day",allDay.isChecked()));
        }
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
