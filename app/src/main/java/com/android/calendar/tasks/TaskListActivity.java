package com.android.calendar.tasks;

import android.Manifest;
import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.net.Uri;
import android.view.View;
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
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import ws.xsoh.etar.R;

/** Lightweight local task editor and list manager. */
public final class TaskListActivity extends AppCompatActivity {
    private TaskStore store;
    private LinearLayout rows;
    private long listId=0;
    private boolean editorOnly, finishAfterPermission, waitingForPermissionSettings;
    private boolean restoredPermissionFlow;
    private Bundle restoredDraft;
    private java.util.function.Supplier<Bundle> captureDraft;
    private static final String[] RULES={null,"FREQ=DAILY","FREQ=WEEKLY","FREQ=MONTHLY","FREQ=YEARLY"};
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String name(TaskStore.TaskList l){return l.name.isEmpty()?getString(R.string.tasks_default_list):l.name;}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        DynamicThemeKt.applyTheme(this);
        store=TaskStore.get(this);
        editorOnly=getIntent().getBooleanExtra("new_task",false)||getIntent().hasExtra("task_id");

        listId=getIntent().getLongExtra("list_id",0);
        if(state!=null){
            listId=state.getLong("list_id");
            finishAfterPermission=state.getBoolean("finish_after_permission");
            waitingForPermissionSettings=state.getBoolean("waiting_for_permission_settings");
            restoredPermissionFlow=finishAfterPermission || waitingForPermissionSettings;
            restoredDraft=state.getBundle("editor_draft");
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
        // All normal task browsing now lives in UndatedTasksFragment.
        if (!getIntent().getBooleanExtra("manage_lists", false)) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(8),dp(16),dp(8));
        LinearLayout header = new LinearLayout(this);
        header.addView(button(R.string.tasks_back, () -> finish()));
        TextView heading = new TextView(this);
        heading.setText(R.string.tasks_manage_lists);
        heading.setTextSize(22);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(heading,new LinearLayout.LayoutParams(0,-1,1));
        header.addView(button(R.string.tasks_new_list, () -> listName(null)));
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("list_id", listId);
        state.putBoolean("finish_after_permission", finishAfterPermission);
        state.putBoolean("waiting_for_permission_settings", waitingForPermissionSettings);
        if (captureDraft != null) state.putBundle("editor_draft", captureDraft.get());
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        if (store != null) refresh();
        if (waitingForPermissionSettings || restoredPermissionFlow) {
            waitingForPermissionSettings = false;
            restoredPermissionFlow = false;
            TaskAlarmReceiver.schedule(this);
            finishEditorAfterPermission();
        }
    }

    private Button button(int label,Runnable callback){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->callback.run());return b;}
    private TaskStore.Task newTask(){TaskStore.Task t=new TaskStore.Task();t.listId=listId==0?1:listId;return t;}
    private ArrayAdapter<String> adapter(List<String> labels){ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return a;}
    private void refresh() {
        if (rows == null) return;
        rows.removeAllViews();
        for (TaskStore.TaskList list : store.lists()) {
            Button entry = new Button(this);
            entry.setText(name(list));
            entry.setOnClickListener(v -> manageList(list));
            rows.addView(entry);
        }
    }
    private void manageList(TaskStore.TaskList l){
        new AlertDialog.Builder(this).setTitle(name(l))
            .setItems(l.id==1?new String[]{getString(R.string.tasks_rename)}:
                new String[]{getString(R.string.tasks_rename),getString(R.string.tasks_delete)},(d,w)->{
                if(w==0)listName(l);else new AlertDialog.Builder(this).setMessage(R.string.tasks_delete_list_confirm)
                    .setNegativeButton(android.R.string.cancel,null)
                    .setPositiveButton(R.string.tasks_delete,(x,y)->{
                        store.deleteList(l.id);refresh();
                    }).show();
            }).show();
    }
    private void listName(TaskStore.TaskList l){
        EditText input=new EditText(this);input.setSingleLine();if(l!=null)input.setText(name(l));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(l==null?R.string.tasks_new_list:R.string.tasks_rename).setView(input).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{String text=input.getText().toString().trim();if(text.isEmpty()){input.setError(getString(R.string.tasks_required));return;}if(l==null)listId=store.addList(text);else store.renameList(l.id,text);refresh();dialog.dismiss();}));dialog.show();
    }
    private void label(LinearLayout parent,int text){TextView v=new TextView(this);v.setText(text);v.setPadding(0,dp(10),0,0);parent.addView(v);}
    private void edit(TaskStore.Task task){
        final boolean reminder = task.due != null;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),0,dp(20),dp(8));
        EditText title=new EditText(this);title.setHint(R.string.tasks_task_name);title.setSingleLine();title.setText(task.title);form.addView(title);
        EditText notes=new EditText(this);notes.setHint(R.string.tasks_notes);notes.setText(task.notes);form.addView(notes);
        CheckBox completed=reminder?ReminderCompletion.create(this):new CheckBox(this);
        completed.setText(R.string.tasks_complete);completed.setChecked(task.done);
        if(task.id!=0)form.addView(completed);
        List<TaskStore.TaskList> available=store.lists();ArrayList<String> names=new ArrayList<>();int listPos=0;
        for(int i=0;i<available.size();i++){names.add(name(available.get(i)));if(available.get(i).id==task.listId)listPos=i;}
        label(form,R.string.tasks_lists);Spinner list=new Spinner(this);list.setAdapter(adapter(names));list.setSelection(listPos);form.addView(list);
        CheckBox hasDate=new CheckBox(this);hasDate.setText(R.string.tasks_date);hasDate.setChecked(reminder);hasDate.setEnabled(false);
        CheckBox hasTime=new CheckBox(this);hasTime.setText(R.string.tasks_time);hasTime.setChecked(reminder&&!task.allDay);
        ZoneId zone=task.tz==null||task.allDay?ZoneId.systemDefault():ZoneId.of(task.tz);ZonedDateTime initial=task.due==null?ZonedDateTime.now(zone):Instant.ofEpochMilli(task.due).atZone(task.allDay?ZoneOffset.UTC:zone);
        LocalDate[] date={initial.toLocalDate()};LocalTime[] time={initial.toLocalTime().withSecond(0).withNano(0)};
        Button dateButton=new Button(this);Button timeButton=new Button(this);
        Runnable update=()->{dateButton.setText(date[0].format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)));timeButton.setText(time[0].format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)));};
        dateButton.setOnClickListener(v->new DatePickerDialog(this,(p,y,m,d)->{date[0]=LocalDate.of(y,m+1,d);update.run();},date[0].getYear(),date[0].getMonthValue()-1,date[0].getDayOfMonth()).show());
        timeButton.setOnClickListener(v->new TimePickerDialog(this,(p,h,m)->{time[0]=LocalTime.of(h,m);update.run();},time[0].getHour(),time[0].getMinute(),android.text.format.DateFormat.is24HourFormat(this)).show());
        if(reminder){form.addView(dateButton);form.addView(hasTime);form.addView(timeButton);}update.run();
        CheckBox alarm=new CheckBox(this);alarm.setText(R.string.tasks_notify);alarm.setChecked(task.alarm);if(reminder)form.addView(alarm);
        if(reminder)label(form,R.string.tasks_repeat);Spinner repeat=new Spinner(this);repeat.setAdapter(adapter(java.util.Arrays.asList(getResources().getStringArray(R.array.tasks_repeat_options))));for(int i=0;i<RULES.length;i++)if(java.util.Objects.equals(task.rule,RULES[i]))repeat.setSelection(i);if(reminder)form.addView(repeat);
        label(form,R.string.tasks_priority);Spinner priority=new Spinner(this);priority.setAdapter(adapter(java.util.Arrays.asList(getResources().getStringArray(R.array.tasks_priority_options))));priority.setSelection(task.priority==1?1:task.priority==5?2:task.priority==9?3:0);form.addView(priority);
        Runnable enabled=()->{dateButton.setEnabled(hasDate.isChecked());hasTime.setEnabled(hasDate.isChecked());timeButton.setEnabled(hasDate.isChecked()&&hasTime.isChecked());alarm.setEnabled(hasDate.isChecked()&&hasTime.isChecked());repeat.setEnabled(hasDate.isChecked());};
        hasDate.setOnCheckedChangeListener((b,c)->enabled.run());hasTime.setOnCheckedChangeListener((b,c)->enabled.run());
        LocalDate initialDate = date[0];
        LocalTime initialTime = time[0];
        boolean initialHasDate = hasDate.isChecked(), initialHasTime = hasTime.isChecked();
        if (restoredDraft != null) {
            Bundle draft = restoredDraft;
            restoredDraft = null;
            title.setText(draft.getString("title", task.title));
            notes.setText(draft.getString("notes", task.notes));
            completed.setChecked(draft.getBoolean("complete", task.done));
            long selectedList = draft.getLong("list_id", task.listId);
            for (int i = 0; i < available.size(); i++) {
                if (available.get(i).id == selectedList) list.setSelection(i);
            }
            hasDate.setChecked(reminder);
            hasTime.setChecked(reminder && draft.getBoolean("has_time", initialHasTime));
            date[0] = LocalDate.ofEpochDay(draft.getLong("date", initialDate.toEpochDay()));
            time[0] = LocalTime.ofSecondOfDay(draft.getLong("time", initialTime.toSecondOfDay()));
            alarm.setChecked(draft.getBoolean("alarm", task.alarm));
            repeat.setSelection(draft.getInt("repeat", repeat.getSelectedItemPosition()));
            priority.setSelection(draft.getInt("priority", priority.getSelectedItemPosition()));
            update.run();
        }
        enabled.run();
        captureDraft = () -> {
            Bundle draft = new Bundle();
            draft.putString("title", title.getText().toString());
            draft.putString("notes", notes.getText().toString());
            draft.putBoolean("complete", completed.isChecked());
            draft.putLong("list_id", available.get(list.getSelectedItemPosition()).id);
            draft.putBoolean("has_date", hasDate.isChecked());
            draft.putBoolean("has_time", hasTime.isChecked());
            draft.putLong("date", date[0].toEpochDay());
            draft.putLong("time", time[0].toSecondOfDay());
            draft.putBoolean("alarm", alarm.isChecked());
            draft.putInt("repeat", repeat.getSelectedItemPosition());
            draft.putInt("priority", priority.getSelectedItemPosition());
            return draft;
        };
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(reminder ? (task.id==0?R.string.reminders_add:R.string.reminders_edit) : (task.id==0?R.string.tasks_add:R.string.tasks_edit)).setView(scroll).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null);
        if(task.id!=0)builder.setNeutralButton(R.string.tasks_delete,null);
        AlertDialog dialog=builder.create();
        dialog.setOnDismissListener(d->{
            captureDraft = null;
            if(editorOnly&&!finishAfterPermission&&!isChangingConfigurations())finish();
        });
        dialog.setOnShowListener(v->{
            if(task.id!=0)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b->
                new AlertDialog.Builder(this).setMessage(reminder ? R.string.reminders_delete_confirm : R.string.tasks_delete_confirm)
                    .setNegativeButton(android.R.string.cancel,null)
                    .setPositiveButton(R.string.tasks_delete,(dd,ww)->{
                        store.delete(task.id);dialog.dismiss();
                    }).show());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
            String text=title.getText().toString().trim();if(text.isEmpty()){title.setError(getString(R.string.tasks_required));return;}
            task.title=text;task.notes=notes.getText().toString();task.listId=available.get(list.getSelectedItemPosition()).id;
            Long oldDue=task.due;String oldRule=task.rule;
            boolean scheduleEdited = initialHasDate != hasDate.isChecked()
                    || (hasDate.isChecked() && (initialHasTime != hasTime.isChecked()
                    || !initialDate.equals(date[0])
                    || (hasTime.isChecked() && !initialTime.equals(time[0]))));
            task.allDay=hasDate.isChecked()&&!hasTime.isChecked();
            if (scheduleEdited || task.id == 0) {
                task.tz=task.allDay?"UTC":zone.getId();
                task.due=!hasDate.isChecked()?null:task.allDay?date[0].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli():date[0].atTime(time[0]).atZone(zone).toInstant().toEpochMilli();
            }
            task.rule=hasDate.isChecked()?RULES[repeat.getSelectedItemPosition()]:null;
            if(scheduleEdited || !java.util.Objects.equals(oldDue,task.due)||!java.util.Objects.equals(oldRule,task.rule)||task.anchor==null) {
                task.anchor=task.due;
                task.repeatMinute=task.due==null||task.allDay?null:time[0].getHour()*60+time[0].getMinute();
            }
            task.priority=new int[]{0,1,5,9}[priority.getSelectedItemPosition()];task.alarm=reminder&&alarm.isChecked()&&alarm.isEnabled();
            if(!reminder){task.due=null;task.anchor=null;task.repeatMinute=null;task.tz=null;task.allDay=false;task.rule=null;}
            long saved = store.save(task, completed.isChecked());
            finishAfterPermission=saved>0&&editorOnly&&task.alarm&&!task.done;
            dialog.dismiss();
            if(finishAfterPermission)checkAlarmPermissions();
            });
        });dialog.show();
    }
    private void checkAlarmPermissions(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);return;}
        if(Build.VERSION.SDK_INT>=31&&!((AlarmManager)getSystemService(ALARM_SERVICE)).canScheduleExactAlarms()){
            AlertDialog permissionDialog=new AlertDialog.Builder(this).setMessage(R.string.tasks_exact_alarm_message)
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
