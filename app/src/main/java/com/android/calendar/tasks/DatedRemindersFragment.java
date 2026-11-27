package com.android.calendar.tasks;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.InsetDrawable;
import android.widget.ImageView;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.android.calendar.CalendarController;
import com.android.calendar.DatedItemsRepository;
import com.android.calendar.DatedListUi;
import com.android.calendar.Utils;
import com.android.calendar.theme.DynamicThemeKt;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private boolean firstResume = true;
    private LocalDate initialDate;
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
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        Context context = requireContext();
        store = TaskStore.get(context);
        controller = CalendarController.getInstance(requireActivity());
        firstResume = true;
        initialDate = Instant.ofEpochMilli(controller.getTime()).atZone(zone()).toLocalDate();
        if (state != null) {
            listId = state.getLong("list_id");
            showCompleted = state.getBoolean("completed");
        }
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        filter = new Spinner(context);
        root.addView(filter, new LinearLayout.LayoutParams(-1,-2));
        adapter = new ReminderAdapter();
        list = DatedListUi.createList(context, adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Row row = rows.get(position);
            if (row.completedCount >= 0) {
                showCompleted = !showCompleted;
                refresh(true);
            } else if (row.task != null) {
                TaskCalendarUi.openTask(requireContext(), row.task.id);
            }
        });
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
        DatedListUi.listenToScroll(list, this::updateScrolledTitle);
        refresh(false);
        scrollTo(initialDate);
        return root;
    }

    @Override public void onResume() {
        super.onResume();
        reloadLists();
        if (firstResume) {
            firstResume = false;
            refresh(false);
            scrollTo(initialDate);
        } else {
            refresh(true);
        }
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
        lists=store.visibleLists();
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

    private void appendDays(List<DatedItemsRepository.Item<TaskStore.Task>> tasks, boolean includeToday) {
        DatedListUi.appendDays(rows, tasks, includeToday ? LocalDate.now(zone()) : null,
                date -> new Row(date, null, -1), item -> new Row(item.day, item.value, -1));
    }

    private void refresh(boolean preservePosition) {
        if (list == null || !isAdded()) return;
        int first=list.getFirstVisiblePosition();
        Row previousRow=first<rows.size()?rows.get(first):null;
        LocalDate previous=previousRow==null?null:previousRow.day;
        int offset=DatedListUi.topOffset(list);
        List<DatedItemsRepository.Item<TaskStore.Task>> pending = new ArrayList<>(), completed = new ArrayList<>();
        for (DatedItemsRepository.Item<TaskStore.Task> item : DatedItemsRepository.reminders(store, listId, zone())) {
            (item.value.done ? completed : pending).add(item);
        }
        binding=true;
        rows.clear();
        appendDays(pending,true);
        if(pending.isEmpty() && completed.isEmpty()) rows.add(new Row(null,null,-2));
        if(!completed.isEmpty()){
            rows.add(new Row(null,null,completed.size()));
            if(showCompleted)appendDays(completed,false);
        }
        adapter.notifyDataSetChanged();
        if(preservePosition && previousRow!=null) {
            int index=-1;
            for(int i=0;i<rows.size();i++){
                Row row=rows.get(i);
                if(previousRow.task!=null && row.task!=null && previousRow.task.id==row.task.id
                        && previousRow.task.done==row.task.done){index=i;break;}
                if(previousRow.completedCount>=0 && row.completedCount>=0){index=i;break;}
            }
            if(index<0 && previous!=null)index=findDay(previous);
            if(index>=0)list.setSelectionFromTop(index,offset);
        }
        binding=false;
    }

    private int findDay(LocalDate day) {
        return Math.max(0, DatedListUi.findHeader(rows, day,
                row -> row.task == null ? row.day : null, row -> row.completedCount >= 0));
    }

    private void updateScrolledTitle(int first) {
        if(binding || first<0 || first>=rows.size() || !isAdded())return;
        LocalDate date=rows.get(first).day;
        if(date!=null && !date.equals(topDay))publishTitle(date);
    }

    private void scrollTo(LocalDate target) {
        if(list==null)return;
        int index=findDay(target);
        list.setSelectionFromTop(index,0);
        LocalDate actual=rows.isEmpty()?target:rows.get(index).day;
        publishTitle(actual==null?target:actual);
    }

    private void publishTitle(LocalDate date) {
        if(list==null||!isAdded())return;
        topDay=date;
        list.post(()->{
            if(list==null||!isAdded()||!date.equals(topDay)||controller.getViewType()!=CalendarController.ViewType.REMINDERS)return;
            DatedListUi.publishDate(controller, this, zone(), date);
        });
    }

    @Override public long getSupportedEventTypes(){
        return CalendarController.EventType.GO_TO | CalendarController.EventType.EVENTS_CHANGED;
    }
    @Override public void handleEvent(CalendarController.EventInfo event){
        if(event.eventType==CalendarController.EventType.GO_TO && list!=null && event.startTime!=null){
            LocalDate target=Instant.ofEpochMilli(event.startTime.toMillis()).atZone(zone()).toLocalDate();
            if(firstResume)initialDate=target;
            scrollTo(target);
        }else if(event.eventType==CalendarController.EventType.EVENTS_CHANGED)eventsChanged();
    }
    @Override public void eventsChanged(){refresh(true);}

    /** Reuse the agenda's spacing, text styles and theme resources without
     * tying reminder rows to CalendarProvider or its cursor adapter. */
    private final class ReminderRowView {
        final LinearLayout root;
        final CheckBox done;
        final TextView title;
        final TextView subtitle;
        final ImageView bell;

        ReminderRowView(Context context) {
            DatedListUi.RowLayout layout = new DatedListUi.RowLayout(context);
            root = layout.root;

            // The agenda's color chip occupies 16 + 24 + 8 = 48dp.
            // Match its text start while retaining a 48dp checkbox touch target.
            done = ReminderCompletion.create(context);
            done.setButtonDrawable(new InsetDrawable(done.getButtonDrawable(), dp(16), 0, dp(8), 0));
            done.setGravity(Gravity.CENTER_VERTICAL);
            layout.addLeading(done);
            title = layout.addText(18);
            subtitle = layout.addText(14);

            bell = new ImageView(context);
            bell.setImageResource(R.drawable.ic_reminder_outline);
            bell.setContentDescription(getString(R.string.tasks_notify));
            LinearLayout.LayoutParams bellParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            bellParams.setMarginStart(dp(8));
            layout.item.addView(bell, bellParams);
            root.setTag(this);
        }

        void bind(Row row, Context context) {
            TaskStore.Task task = row.task;
            int standardColor = DynamicThemeKt.getColor(context, "agenda_item_standard_color");
            int secondaryColor = DynamicThemeKt.getColor(context, "agenda_item_where_text_color");
            root.setBackgroundResource(DynamicThemeKt.getDrawableId(context,
                    task.done ? "agenda_item_bg_secondary" : "agenda_item_bg_primary"));
            // Detach the previous row's listener before rebinding a recycled checkbox.
            done.setOnCheckedChangeListener(null);
            done.setChecked(task.done);
            done.setContentDescription(getString(R.string.tasks_complete) + " " + task.title);
            done.setOnCheckedChangeListener((button, checked) -> store.complete(task.id, checked, task.due));
            title.setText(task.title);
            title.setTextColor(standardColor);
            title.setTypeface(task.done ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
            title.setPaintFlags(task.done ? title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                    : title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            String when = task.allDay ? getString(R.string.reminders_all_day)
                    : Utils.formatDateRange(context, task.due, task.due, DateUtils.FORMAT_SHOW_TIME);
            boolean overdue = !task.done && (task.allDay ? row.day.isBefore(LocalDate.now(zone()))
                    : task.due < System.currentTimeMillis());
            subtitle.setText(overdue ? when + " · " + getString(R.string.reminders_overdue) : when);
            subtitle.setTextColor(overdue ? com.google.android.material.color.MaterialColors.getColor(
                    context, androidx.appcompat.R.attr.colorError, secondaryColor) : secondaryColor);
            bell.setVisibility(task.alarm ? View.VISIBLE : View.GONE);
            bell.setColorFilter(secondaryColor);
            root.setOnClickListener(v -> TaskCalendarUi.openTask(context, task.id));
        }
    }

    private final class ReminderAdapter extends BaseAdapter {
        @Override public int getCount(){return rows.size();}
        @Override public Object getItem(int position){return rows.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public boolean areAllItemsEnabled(){return false;}
        @Override public boolean isEnabled(int position){
            Row row=rows.get(position);
            return row.completedCount>=0||row.task!=null;
        }
        @Override public int getViewTypeCount(){return 4;}
        @Override public int getItemViewType(int position){
            Row row=rows.get(position);
            return row.completedCount==-2?3:row.completedCount>=0?2:row.task==null?0:1;
        }
        @Override public View getView(int position,View convertView,ViewGroup parent){
            Row row=rows.get(position);
            Context context=parent.getContext();
            if(row.completedCount==-2){
                TextView empty=convertView instanceof TextView ? (TextView)convertView : new TextView(context);
                empty.setPadding(dp(16),dp(20),dp(16),dp(20));
                empty.setTextColor(DynamicThemeKt.getColor(context,"agenda_item_where_text_color"));
                empty.setBackgroundResource(DynamicThemeKt.getDrawableId(context,"agenda_item_bg_primary"));
                empty.setText(R.string.reminders_empty);
                return empty;
            }
            if(row.completedCount>=0){
                TextView heading=convertView instanceof TextView ? (TextView)convertView : new TextView(context);
                heading.setPadding(dp(16),dp(14),dp(16),dp(14));
                heading.setTextColor(DynamicThemeKt.getColor(context,"agenda_item_standard_color"));
                heading.setBackgroundResource(DynamicThemeKt.getDrawableId(context,"agenda_item_bg_secondary"));
                heading.setText((showCompleted?"▾ ":"▸ ")+getString(R.string.reminders_completed,row.completedCount));
                heading.setOnClickListener(v->{showCompleted=!showCompleted;refresh(true);});
                return heading;
            }
            if(row.task==null){
                return DatedListUi.dateHeader(convertView, parent, row.day, zone());
            }
            ReminderRowView holder=convertView != null && convertView.getTag() instanceof ReminderRowView
                    ? (ReminderRowView)convertView.getTag() : new ReminderRowView(context);
            holder.bind(row,context);
            return holder.root;
        }
    }
}
