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
    private LinearLayout root, rows;
    private Spinner filter;
    private long listId=0;
    private List<TaskStore.TaskList> lists;
    private static final String[] RULES={null,"FREQ=DAILY","FREQ=WEEKLY","FREQ=MONTHLY","FREQ=YEARLY"};
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String name(TaskStore.TaskList l){return l.name.isEmpty()?getString(R.string.tasks_default_list):l.name;}
    @Override public void onCreate(Bundle state) {
        DynamicThemeKt.applyTheme(this);
        super.onCreate(state);
        store=TaskStore.get(this);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(8),dp(16),dp(8));
        LinearLayout header=new LinearLayout(this);
        Button back=button(R.string.tasks_back,()->finish());header.addView(back);
        TextView heading=new TextView(this);heading.setText(R.string.tasks_title);heading.setTextSize(22);heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(heading,new LinearLayout.LayoutParams(0,-1,1));header.addView(button(R.string.tasks_add,()->edit(newTask())));root.addView(header);
        LinearLayout controls=new LinearLayout(this);filter=new Spinner(this);controls.addView(filter,new LinearLayout.LayoutParams(0,-2,1));controls.addView(button(R.string.tasks_lists,this::manageLists));root.addView(controls);
        ScrollView scroll=new ScrollView(this);rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        if(state!=null)listId=state.getLong("list_id");
        reloadLists();
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){listId=position==0?0:lists.get(position-1).id;refresh();}
        });
        if(state==null){long id=getIntent().getLongExtra("task_id",0);TaskStore.Task t=store.find(id);
            if(t!=null)edit(t);else if(getIntent().getBooleanExtra("new_task",false)){TaskStore.Task n=newTask();long date=getIntent().getLongExtra("date",0);if(date>0){n.allDay=true;n.due=Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();n.anchor=n.due;}edit(n);}
        }
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putLong("list_id",listId);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();if(store!=null){refresh();TaskAlarmReceiver.schedule(this);}}
    private Button button(int label,Runnable callback){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->callback.run());return b;}
    private TaskStore.Task newTask(){TaskStore.Task t=new TaskStore.Task();t.listId=listId==0?1:listId;return t;}
    private ArrayAdapter<String> adapter(List<String> labels){ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return a;}
    private void reloadLists(){
        lists=store.lists();ArrayList<String> labels=new ArrayList<>();labels.add(getString(R.string.tasks_all));int selected=0;
        for(int i=0;i<lists.size();i++){labels.add(name(lists.get(i)));if(lists.get(i).id==listId)selected=i+1;}
        if(selected==0)listId=0;filter.setAdapter(adapter(labels));filter.setSelection(selected);refresh();
    }
    private String dateLabel(TaskStore.Task t){
        if(t.due==null)return getString(R.string.tasks_no_date);
        ZoneId zone=t.allDay?ZoneOffset.UTC:ZoneId.systemDefault();
        ZonedDateTime d=Instant.ofEpochMilli(t.due).atZone(zone);
        return t.allDay?d.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)):
            d.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT));
    }
    private void refresh(){
        if(rows==null)return;rows.removeAllViews();
        List<TaskStore.Task> tasks=store.tasks(listId);
        if(tasks.isEmpty()){TextView empty=new TextView(this);empty.setPadding(0,dp(20),0,0);empty.setText(R.string.tasks_empty);rows.addView(empty);}
        for(TaskStore.Task t:tasks){
            LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            CheckBox done=new CheckBox(this);done.setChecked(t.done);done.setContentDescription(getString(R.string.tasks_complete)+" "+t.title);
            done.setOnCheckedChangeListener((b,checked)->{store.complete(t.id,checked);TaskAlarmReceiver.dismiss(this,t.id);changed();});
            row.addView(done);
            LinearLayout details=new LinearLayout(this);details.setOrientation(LinearLayout.VERTICAL);details.setPadding(dp(6),dp(10),dp(6),dp(10));
            TextView title=new TextView(this);title.setText(t.title);title.setTextSize(18);
            if(t.done)title.setPaintFlags(title.getPaintFlags()|android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            TextView date=new TextView(this);date.setText(dateLabel(t));details.addView(title);details.addView(date);
            details.setOnClickListener(v->edit(t));row.addView(details,new LinearLayout.LayoutParams(0,-2,1));rows.addView(row);
        }
    }
    private void changed(){refresh();TaskAlarmReceiver.schedule(this);}
    private void manageLists(){
        List<TaskStore.TaskList> current=store.lists();String[] labels=new String[current.size()+1];labels[0]=getString(R.string.tasks_new_list);
        for(int i=0;i<current.size();i++)labels[i+1]=name(current.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.tasks_lists).setItems(labels,(d,w)->{
            if(w==0)listName(null);else{TaskStore.TaskList l=current.get(w-1);new AlertDialog.Builder(this).setTitle(name(l))
                .setItems(l.id==1?new String[]{getString(R.string.tasks_rename)}:new String[]{getString(R.string.tasks_rename),getString(R.string.tasks_delete)},(dd,ww)->{
                    if(ww==0)listName(l);else new AlertDialog.Builder(this).setMessage(R.string.tasks_delete_list_confirm).setNegativeButton(android.R.string.cancel,null)
                        .setPositiveButton(R.string.tasks_delete,(x,y)->{for(TaskStore.Task t:store.tasks(l.id))TaskAlarmReceiver.dismiss(this,t.id);store.deleteList(l.id);reloadLists();TaskAlarmReceiver.schedule(this);}).show();
                }).show();}
        }).show();
    }
    private void listName(TaskStore.TaskList l){
        EditText input=new EditText(this);input.setSingleLine();if(l!=null)input.setText(name(l));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(l==null?R.string.tasks_new_list:R.string.tasks_rename).setView(input).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{String text=input.getText().toString().trim();if(text.isEmpty()){input.setError(getString(R.string.tasks_required));return;}if(l==null)listId=store.addList(text);else store.renameList(l.id,text);reloadLists();dialog.dismiss();}));dialog.show();
    }
    private void label(LinearLayout parent,int text){TextView v=new TextView(this);v.setText(text);v.setPadding(0,dp(10),0,0);parent.addView(v);}
    private void edit(TaskStore.Task task){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),0,dp(20),dp(8));
        EditText title=new EditText(this);title.setHint(R.string.tasks_task_name);title.setSingleLine();title.setText(task.title);form.addView(title);
        EditText notes=new EditText(this);notes.setHint(R.string.tasks_notes);notes.setText(task.notes);form.addView(notes);
        List<TaskStore.TaskList> available=store.lists();ArrayList<String> names=new ArrayList<>();int listPos=0;
        for(int i=0;i<available.size();i++){names.add(name(available.get(i)));if(available.get(i).id==task.listId)listPos=i;}
        label(form,R.string.tasks_lists);Spinner list=new Spinner(this);list.setAdapter(adapter(names));list.setSelection(listPos);form.addView(list);
        CheckBox hasDate=new CheckBox(this);hasDate.setText(R.string.tasks_date);hasDate.setChecked(task.due!=null);form.addView(hasDate);
        CheckBox hasTime=new CheckBox(this);hasTime.setText(R.string.tasks_time);hasTime.setChecked(task.due!=null&&!task.allDay);form.addView(hasTime);
        ZoneId zone=ZoneId.systemDefault();ZonedDateTime initial=task.due==null?ZonedDateTime.now(zone):Instant.ofEpochMilli(task.due).atZone(task.allDay?ZoneOffset.UTC:zone);
        LocalDate[] date={initial.toLocalDate()};LocalTime[] time={initial.toLocalTime().withSecond(0).withNano(0)};
        Button dateButton=new Button(this);Button timeButton=new Button(this);
        Runnable update=()->{dateButton.setText(date[0].format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)));timeButton.setText(time[0].format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)));};
        dateButton.setOnClickListener(v->new DatePickerDialog(this,(p,y,m,d)->{date[0]=LocalDate.of(y,m+1,d);update.run();},date[0].getYear(),date[0].getMonthValue()-1,date[0].getDayOfMonth()).show());
        timeButton.setOnClickListener(v->new TimePickerDialog(this,(p,h,m)->{time[0]=LocalTime.of(h,m);update.run();},time[0].getHour(),time[0].getMinute(),android.text.format.DateFormat.is24HourFormat(this)).show());
        form.addView(dateButton);form.addView(timeButton);update.run();
        CheckBox alarm=new CheckBox(this);alarm.setText(R.string.tasks_notify);alarm.setChecked(task.alarm);form.addView(alarm);
        label(form,R.string.tasks_repeat);Spinner repeat=new Spinner(this);repeat.setAdapter(adapter(java.util.Arrays.asList(getResources().getStringArray(R.array.tasks_repeat_options))));for(int i=0;i<RULES.length;i++)if(java.util.Objects.equals(task.rule,RULES[i]))repeat.setSelection(i);form.addView(repeat);
        label(form,R.string.tasks_priority);Spinner priority=new Spinner(this);priority.setAdapter(adapter(java.util.Arrays.asList(getResources().getStringArray(R.array.tasks_priority_options))));priority.setSelection(task.priority==1?1:task.priority==5?2:task.priority==9?3:0);form.addView(priority);
        Runnable enabled=()->{dateButton.setEnabled(hasDate.isChecked());hasTime.setEnabled(hasDate.isChecked());timeButton.setEnabled(hasDate.isChecked()&&hasTime.isChecked());alarm.setEnabled(hasDate.isChecked()&&hasTime.isChecked());repeat.setEnabled(hasDate.isChecked());};
        hasDate.setOnCheckedChangeListener((b,c)->enabled.run());hasTime.setOnCheckedChangeListener((b,c)->enabled.run());enabled.run();
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(task.id==0?R.string.tasks_add:R.string.tasks_edit).setView(scroll).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null);
        if(task.id!=0)builder.setNeutralButton(R.string.tasks_delete,(d,w)->new AlertDialog.Builder(this).setMessage(R.string.tasks_delete_confirm).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.tasks_delete,(dd,ww)->{store.delete(task.id);TaskAlarmReceiver.dismiss(this,task.id);changed();}).show());
        AlertDialog dialog=builder.create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
            String text=title.getText().toString().trim();if(text.isEmpty()){title.setError(getString(R.string.tasks_required));return;}
            task.title=text;task.notes=notes.getText().toString();task.listId=available.get(list.getSelectedItemPosition()).id;
            Long oldDue=task.due;String oldRule=task.rule;
            task.allDay=hasDate.isChecked()&&!hasTime.isChecked();
            task.tz=task.allDay?"UTC":zone.getId();
            task.due=!hasDate.isChecked()?null:task.allDay?date[0].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli():date[0].atTime(time[0]).atZone(zone).toInstant().toEpochMilli();
            task.rule=hasDate.isChecked()?RULES[repeat.getSelectedItemPosition()]:null;
            if(!java.util.Objects.equals(oldDue,task.due)||!java.util.Objects.equals(oldRule,task.rule)||task.anchor==null)task.anchor=task.due;
            task.priority=new int[]{0,1,5,9}[priority.getSelectedItemPosition()];task.alarm=alarm.isChecked()&&alarm.isEnabled();
            store.save(task);TaskAlarmReceiver.dismiss(this,task.id);dialog.dismiss();changed();if(task.alarm)checkAlarmPermissions();
        }));dialog.show();
    }
    private void checkAlarmPermissions(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);return;}
        if(Build.VERSION.SDK_INT>=31&&!((AlarmManager)getSystemService(ALARM_SERVICE)).canScheduleExactAlarms()){
            new AlertDialog.Builder(this).setMessage(R.string.tasks_exact_alarm_message).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.tasks_settings,(d,w)->{try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}catch(android.content.ActivityNotFoundException ignored){}}).show();
        }
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);TaskAlarmReceiver.schedule(this);if(code==42&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)checkAlarmPermissions();}
}
