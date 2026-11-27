package com.android.calendar.tasks;

import android.content.Context;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.android.calendar.CalendarController;
import com.android.calendar.Utils;
import com.android.calendar.calendarcommon2.Time;
import com.android.calendar.theme.DynamicThemeKt;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ws.xsoh.etar.R;

/** Dated reminders only. Reuses the native agenda date header without querying CalendarProvider. */
public final class DatedRemindersFragment extends Fragment implements CalendarController.EventHandler {
    private long listId;
    private boolean showCompleted;
    private Spinner filter;
    private ListView list;
    private TaskStore store;
    private List<TaskStore.TaskList> lists;
    private final ArrayList<Row> rows = new ArrayList<>();
    private ReminderAdapter adapter;
    private CalendarController controller;
    private LocalDate topDay;
    private boolean binding;
    private static final class Row {
        final LocalDate day;
        final TaskStore.Task task;
        final int completedCount;
        Row(LocalDate day, TaskStore.Task task, int completedCount) {
            this.day=day; this.task=task; this.completedCount=completedCount;
        }
    }

    public long getListId() { return listId; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private ZoneId zone() { return ZoneId.of(Utils.getTimeZone(requireContext(), null)); }
    private LocalDate day(TaskStore.Task task) {
        return Instant.ofEpochMilli(task.due).atZone(task.allDay ? ZoneOffset.UTC : zone()).toLocalDate();
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        Context context = requireContext();
        store = TaskStore.get(context);
        controller = CalendarController.getInstance(requireActivity());
        if (state != null) {
            listId = state.getLong("list_id");
            showCompleted = state.getBoolean("completed");
        }
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        filter = new Spinner(context);
        root.addView(filter, new LinearLayout.LayoutParams(-1,-2));
        list = new ListView(context);
        list.setDivider(null);
        list.setPadding(0,0,0,dp(80));
        list.setClipToPadding(false);
        adapter = new ReminderAdapter();
        list.setAdapter(adapter);
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        reloadLists();
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (lists == null || position > lists.size()) return;
                long selected = position == 0 ? 0 : lists.get(position-1).id;
                if (listId != selected) {
                    listId = selected;
                    refresh(true);
                }
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view,int state) {}
            @Override public void onScroll(AbsListView view,int first,int count,int total) {
                if (binding || first >= rows.size() || !isAdded()) return;
                LocalDate day = rows.get(first).day;
                if (day == null || day.equals(topDay)) return;
                topDay = day;
                publishTitle(day);
            }
        });
        refresh(false);
        scrollTo(Instant.ofEpochMilli(controller.getTime()).atZone(zone()).toLocalDate());
        return root;
    }

    @Override public void onResume() {
        super.onResume();
        reloadLists();
        refresh(true);
        TaskAlarmReceiver.schedule(requireContext());
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putLong("list_id",listId);
        state.putBoolean("completed",showCompleted);
        super.onSaveInstanceState(state);
    }

    @Override public void onDestroyView() {
        list=null; filter=null; adapter=null; rows.clear();
        super.onDestroyView();
    }

    private void reloadLists() {
        if (filter == null) return;
        lists=store.lists();
        ArrayList<String> names=new ArrayList<>();
        names.add(getString(R.string.reminders_all));
        int position=0;
        for (int i=0;i<lists.size();i++) {
            TaskStore.TaskList item=lists.get(i);
            names.add(item.name.isEmpty()?getString(R.string.tasks_default_list):item.name);
            if (item.id==listId) position=i+1;
        }
        if(position==0)listId=0;
        ArrayAdapter<String> labels=new ArrayAdapter<>(requireContext(),android.R.layout.simple_spinner_item,names);
        labels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(labels);
        filter.setSelection(position);
    }

    private void appendDays(List<TaskStore.Task> tasks, boolean includeToday) {
        LocalDate last=null;
        LocalDate today=LocalDate.now(zone());
        boolean todayAdded=false;
        for (TaskStore.Task task : tasks) {
            LocalDate date=day(task);
            if (includeToday && !todayAdded && date.isAfter(today)) {
                rows.add(new Row(today,null,-1));
                todayAdded=true;
            }
            if (!date.equals(last)) {
                rows.add(new Row(date,null,-1));
                last=date;
            }
            if(date.equals(today))todayAdded=true;
            rows.add(new Row(date,task,-1));
        }
        if (includeToday && !todayAdded) rows.add(new Row(today,null,-1));
    }

    private void refresh(boolean preservePosition) {
        if (list == null || !isAdded()) return;
        int first=list.getFirstVisiblePosition();
        LocalDate previous=first<rows.size()?rows.get(first).day:null;
        int offset=list.getChildCount()>0?list.getChildAt(0).getTop():0;
        List<TaskStore.Task> pending=new ArrayList<>(), completed=new ArrayList<>();
        for (TaskStore.Task task:store.tasks(listId)) {
            if(task.due==null)continue;
            (task.done?completed:pending).add(task);
        }
        Comparator<TaskStore.Task> order=Comparator.comparing(this::day)
                .thenComparing(task->!task.allDay)
                .thenComparingLong(task->task.due)
                .thenComparingLong(task->task.id);
        pending.sort(order); completed.sort(order);
        binding=true;
        rows.clear();
        appendDays(pending,true);
        if(!completed.isEmpty()){
            rows.add(new Row(null,null,completed.size()));
            if(showCompleted)appendDays(completed,false);
        }
        adapter.notifyDataSetChanged();
        if(preservePosition && previous!=null) {
            int index=findDay(previous);
            list.setSelectionFromTop(index,offset);
        }
        binding=false;
    }

    private int findDay(LocalDate day) {
        int last=0;
        for(int i=0;i<rows.size();i++){
            Row row=rows.get(i);
            if(row.completedCount>=0)break;
            if(row.task==null && row.day!=null){
                last=i;
                if(!row.day.isBefore(day))return i;
            }
        }
        return last;
    }

    private void scrollTo(LocalDate target) {
        if(list==null)return;
        int index=findDay(target);
        list.setSelection(index);
        publishTitle(target);
    }

    private void publishTitle(LocalDate date) {
        if(list==null||!isAdded())return;
        list.post(()->{
            if(list==null||!isAdded()||controller.getViewType()!=CalendarController.ViewType.REMINDERS)return;
            Time time=new Time(zone().getId());
            time.set(date.atStartOfDay(zone()).toInstant().toEpochMilli());
            controller.setTime(time.toMillis());
            controller.sendEvent(this,CalendarController.EventType.UPDATE_TITLE,time,time,null,-1,
                    CalendarController.ViewType.CURRENT,0,null,null);
        });
    }

    @Override public long getSupportedEventTypes(){
        return CalendarController.EventType.GO_TO | CalendarController.EventType.EVENTS_CHANGED;
    }
    @Override public void handleEvent(CalendarController.EventInfo event){
        if(event.eventType==CalendarController.EventType.GO_TO && list!=null && event.startTime!=null){
            scrollTo(Instant.ofEpochMilli(event.startTime.toMillis()).atZone(zone()).toLocalDate());
        }else if(event.eventType==CalendarController.EventType.EVENTS_CHANGED)eventsChanged();
    }
    @Override public void eventsChanged(){refresh(true);}

    private final class ReminderAdapter extends BaseAdapter {
        @Override public int getCount(){return rows.size();}
        @Override public Object getItem(int position){return rows.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public boolean isEnabled(int position){return false;}
        @Override public int getViewTypeCount(){return 3;}
        @Override public int getItemViewType(int position){
            Row row=rows.get(position);
            return row.completedCount>=0?2:row.task==null?0:1;
        }
        @Override public View getView(int position,View convertView,ViewGroup parent){
            Row row=rows.get(position);
            Context context=parent.getContext();
            if(row.completedCount>=0){
                TextView heading=new TextView(context);
                heading.setPadding(dp(16),dp(14),dp(16),dp(14));
                heading.setText((showCompleted?"▾ ":"▸ ")+getString(R.string.reminders_completed,row.completedCount));
                heading.setOnClickListener(v->{showCompleted=!showCompleted;refresh(true);});
                return heading;
            }
            if(row.task==null){
                View header=getLayoutInflater().inflate(R.layout.agenda_day,parent,false);
                long millis=row.day.atStartOfDay(zone()).toInstant().toEpochMilli();
                int julian=(int)(row.day.toEpochDay()+2440588L);
                int today=(int)(LocalDate.now(zone()).toEpochDay()+2440588L);
                ((TextView)header.findViewById(R.id.day)).setText(Utils.getDayOfWeekString(julian,today,millis,context));
                ((TextView)header.findViewById(R.id.date)).setText(Utils.formatDateRange(context,millis,millis,DateUtils.FORMAT_SHOW_DATE));
                header.setBackgroundResource(DynamicThemeKt.getDrawableId(context,
                        julian>today?"agenda_item_bg_primary":"agenda_item_bg_secondary"));
                return header;
            }
            TaskStore.Task task=row.task;
            LinearLayout item=new LinearLayout(context);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(8),0,dp(16),0);
            item.setMinimumHeight(dp(64));
            CheckBox done=new CheckBox(context);
            done.setChecked(task.done);
            done.setContentDescription(getString(R.string.tasks_complete)+" "+task.title);
            done.setOnCheckedChangeListener((button,checked)->store.complete(task.id,checked,task.due));
            item.addView(done);
            LinearLayout details=new LinearLayout(context);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(dp(8),dp(8),dp(8),dp(8));
            TextView title=new TextView(context);
            title.setText(task.title);
            title.setTextSize(18);
            title.setTypeface(title.getTypeface(),android.graphics.Typeface.BOLD);
            if(task.done)title.setPaintFlags(title.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG);
            details.addView(title);
            TextView subtitle=new TextView(context);
            subtitle.setTextSize(14);
            String when=task.allDay?getString(R.string.reminders_all_day):Utils.formatDateRange(context,task.due,task.due,DateUtils.FORMAT_SHOW_TIME);
            boolean overdue=!task.done && (task.allDay?row.day.isBefore(LocalDate.now(zone())):task.due<System.currentTimeMillis());
            subtitle.setText(overdue?when+" · "+getString(R.string.reminders_overdue):when);
            if(overdue)subtitle.setTextColor(com.google.android.material.color.MaterialColors.getColor(context,com.google.android.material.R.attr.colorError,subtitle.getCurrentTextColor()));
            details.addView(subtitle);
            item.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            if(task.alarm){
                android.widget.ImageView bell=new android.widget.ImageView(context);
                bell.setImageResource(R.drawable.ic_reminder_outline);
                bell.setContentDescription(getString(R.string.tasks_notify));
                item.addView(bell,new LinearLayout.LayoutParams(dp(24),dp(24)));
            }
            details.setOnClickListener(v->TaskCalendarUi.openTask(context,task.id));
            return item;
        }
    }
}
