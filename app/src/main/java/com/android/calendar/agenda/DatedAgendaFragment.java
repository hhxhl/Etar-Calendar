package com.android.calendar.agenda;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.android.calendar.CalendarController;
import com.android.calendar.CalendarController.EventInfo;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.CalendarController.ViewType;
import com.android.calendar.ItemStateSymbols;
import com.android.calendar.DatedItemsRepository;
import com.android.calendar.DatedItemsRepository.AgendaEntry;
import com.android.calendar.DatedListUi;
import com.android.calendar.ViewQueryLoader;
import com.android.calendar.EventCountdown;
import com.android.calendar.Utils;
import com.android.calendar.event.EditEventActivity;
import com.android.calendar.settings.GeneralPreferences;
import com.android.calendar.tasks.TaskStore;
import com.android.calendar.tasks.TaskCalendarUi;
import com.android.calendar.tasks.TaskListUi;
import com.android.calendar.ItemAppearance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;

import ws.xsoh.etar.R;

/** Shared agenda and search results: bounded queries and a single ListView adapter. */
public final class DatedAgendaFragment extends Fragment implements CalendarController.EventHandler {
    private static final String TAG = "DatedAgendaFragment";
    private static final int BLOCK_DAYS = 90;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Row> rows = new ArrayList<>();
    private final ArrayList<Long> calendarIds = new ArrayList<>();
    private final HashSet<Long> writableIds = new HashSet<>();
    private CalendarController controller;
    private Spinner filter;
    private ListView list;
    private final DatedListUi.MinuteRefresh refreshStateSymbols = new DatedListUi.MinuteRefresh();
    private TextView status, searchRange;
    // Null means ordinary agenda; an empty string means search is waiting for a query.
    private String searchQuery;
    private AgendaRows adapter;
    private ViewQueryLoader loader;
    private boolean loading, active, binding;
    private long calendarId = -1;
    private ZoneId zone;
    private LocalDate firstDay, lastDay, targetDay, publishedDay;
    private LocalDate loadedFirstDay, loadedLastDay;
    private Long pendingGoToMillis;
    private Anchor pendingAnchor;
    private boolean hasLoaded;
    private final Runnable reload = () -> {
        if (active && list != null) request(firstDay, lastDay,
                pendingAnchor != null ? pendingAnchor : currentAnchor(), false);
    };

    public static DatedAgendaFragment newInstance(long time, Bundle saved) {
        DatedAgendaFragment fragment = new DatedAgendaFragment();
        Bundle args = new Bundle();
        args.putLong("initial_time", time);
        if (saved != null) args.putBundle("saved_agenda", new Bundle(saved));
        fragment.setArguments(args);
        return fragment;
    }

    public static DatedAgendaFragment forSearch(long time, String query) {
        DatedAgendaFragment fragment = newInstance(time, null);
        fragment.requireArguments().putString("search_query", query == null ? "" : query);
        return fragment;
    }

    public void setSearchQuery(String query) {
        String normalized = query == null ? "" : query.trim();
        if (java.util.Objects.equals(searchQuery, normalized)) return;
        searchQuery = normalized;
        if (list == null) {
            requireArguments().putString("search_query", normalized);
            return;
        }
        LocalDate center = LocalDate.now(zone);
        request(center.minusDays(BLOCK_DAYS), center.plusDays(BLOCK_DAYS),
                new Anchor(center.minusDays(BLOCK_DAYS), -1, -1, -1, 0), true);
    }

    public void refreshSearchSources() {
        if (searchQuery == null || !isResumed() || filter == null) return;
        reloadCalendars();
        eventsChanged();
    }

    public void showToday() {
        if (list != null) goTo(LocalDate.now(zone));
    }

    private static final class Row {
        final LocalDate day;
        final AgendaEntry event;
        final TaskStore.Task task;
        Row(LocalDate day, AgendaEntry event) { this(day, event, null); }
        Row(LocalDate day, AgendaEntry event, TaskStore.Task task) {
            this.day = day;
            this.event = event;
            this.task = task;
        }
        boolean isItem() { return event != null || task != null; }
        boolean allDay() { return event != null ? event.allDay : task.allDay; }
        long sortTime() { return event != null ? event.begin : task.due; }
    }

    private static final class Anchor {
        final LocalDate day;
        final long instance, eventId, begin;
        final int offset;

        Anchor(LocalDate day, long instance, long eventId, long begin, int offset) {
            this.day = day;
            this.instance = instance;
            this.eventId = eventId;
            this.begin = begin;
            this.offset = offset;
        }
    }

    private LocalDate date(long millis) { return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(); }
    private long millis(LocalDate day) { return day.atStartOfDay(zone).toInstant().toEpochMilli(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        Context context = requireContext();
        controller = CalendarController.getInstance(requireActivity());
        zone = ZoneId.of(Utils.getTimeZone(context, null));
        Bundle args = getArguments();
        Bundle state = saved != null ? saved : args == null ? null : args.getBundle("saved_agenda");
        long time = args == null ? controller.getTime() : args.getLong("initial_time", controller.getTime());
        targetDay = date(time);
        searchQuery = state != null && state.containsKey("search_query")
                ? state.getString("search_query") : args == null ? null : args.getString("search_query");
        if (state != null) {
            calendarId = state.getLong("calendar_id", -1);
            targetDay = date(state.getLong("anchor_time", time));
            pendingAnchor = new Anchor(targetDay, state.getLong("instance", -1),
                    state.getLong("event_id", -1), state.getLong("begin", -1), state.getInt("offset", 0));
            firstDay = LocalDate.ofEpochDay(state.getLong("first_day", targetDay.minusDays(BLOCK_DAYS).toEpochDay()));
            lastDay = LocalDate.ofEpochDay(state.getLong("last_day", targetDay.plusDays(BLOCK_DAYS).toEpochDay()));
        }
        if (pendingGoToMillis != null && !date(pendingGoToMillis).equals(targetDay)) {
            targetDay = date(pendingGoToMillis);
            pendingAnchor = null;
            firstDay = lastDay = null;
        }
        pendingGoToMillis = null;
        if (firstDay == null || lastDay == null || targetDay.isBefore(firstDay) || targetDay.isAfter(lastDay)) {
            firstDay = targetDay.minusDays(BLOCK_DAYS);
            lastDay = targetDay.plusDays(BLOCK_DAYS);
        }
        if (pendingAnchor == null) pendingAnchor = new Anchor(
                searchQuery == null ? targetDay : firstDay, -1, -1, -1, 0);
        loader = new ViewQueryLoader();
        hasLoaded = false;
        loadedFirstDay = loadedLastDay = null;
        publishedDay = null;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        filter = new Spinner(context);
        root.addView(filter, new LinearLayout.LayoutParams(-1, -2));
        if (searchQuery != null) {
            LinearLayout navigation = new LinearLayout(context);
            navigation.setGravity(Gravity.CENTER_VERTICAL);
            com.google.android.material.button.MaterialButton previous =
                    new com.google.android.material.button.MaterialButton(context, null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle);
            previous.setText("‹");
            previous.setContentDescription(getString(R.string.agenda_search_previous_range));
            previous.setOnClickListener(v -> shiftSearchRange(false));
            navigation.addView(previous, new LinearLayout.LayoutParams(dp(56), -2));
            searchRange = new TextView(context);
            searchRange.setGravity(Gravity.CENTER);
            searchRange.setTextSize(12);
            navigation.addView(searchRange, new LinearLayout.LayoutParams(0, -2, 1));
            com.google.android.material.button.MaterialButton next =
                    new com.google.android.material.button.MaterialButton(context, null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle);
            next.setText("›");
            next.setContentDescription(getString(R.string.agenda_search_next_range));
            next.setOnClickListener(v -> shiftSearchRange(true));
            navigation.addView(next, new LinearLayout.LayoutParams(dp(56), -2));
            root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
            updateSearchRange();
        }
        status = DatedListUi.statusView(context, null, false);
        status.setVisibility(View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        adapter = new AgendaRows();
        list = DatedListUi.createList(context, adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        reloadCalendars();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= rows.size()) return;
            Row row = rows.get(position);
            if (row.task != null) TaskCalendarUi.openTask(requireContext(), row.task.id);
            else if (row.event != null) EditEventActivity.openEvent(requireContext(), row.event.id,
                    row.event.begin, row.event.end, Attendees.ATTENDEE_STATUS_NONE);
        });
        DatedListUi.listenToScroll(list, this::updateScrolledTitle);
        return root;
    }

    private void shiftSearchRange(boolean forward) {
        if (searchQuery == null || firstDay == null || lastDay == null) return;
        LocalDate first = forward ? lastDay.plusDays(1) : firstDay.minusDays(2 * BLOCK_DAYS + 1);
        LocalDate last = first.plusDays(2 * BLOCK_DAYS);
        request(first, last, new Anchor(first, -1, -1, -1, 0), true);
    }

    private void updateSearchRange() {
        if (searchRange == null || firstDay == null || lastDay == null) return;
        searchRange.setText(Utils.formatDateRange(requireContext(), millis(firstDay), millis(lastDay),
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
    }

    private void reloadCalendars() {
        if (filter == null) return;
        binding = true;
        try {
            ArrayList<String> names = new ArrayList<>();
            names.add(getString(searchQuery == null ? R.string.agenda_all_calendars : R.string.search_all_items));
            calendarIds.clear();
            writableIds.clear();
            calendarIds.add(-1L);
            if (Utils.isCalendarPermissionGranted(requireContext(), false)) {
                try (Cursor cursor = requireContext().getContentResolver().query(Calendars.CONTENT_URI,
                        new String[]{Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_ACCESS_LEVEL},
                        Calendars.VISIBLE + "=1", null, Calendars.CALENDAR_DISPLAY_NAME)) {
                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            long id = cursor.getLong(0);
                            calendarIds.add(id);
                            String name = cursor.isNull(1) ? Long.toString(id) : cursor.getString(1);
                            names.add(searchQuery == null ? name : getString(R.string.search_calendar_source, name));
                            if (cursor.getInt(2) >= Calendars.CAL_ACCESS_CONTRIBUTOR) writableIds.add(id);
                        }
                    }
                } catch (SecurityException error) {
                    Log.w(TAG, "Calendar filter unavailable", error);
                }
            }
            if (searchQuery != null) {
                for (TaskStore.TaskList taskList : TaskStore.get(requireContext()).visibleLists()) {
                    calendarIds.add(-taskList.id - 1);
                    String name = taskList.name.isEmpty() ? getString(R.string.tasks_default_list) : taskList.name;
                    names.add(getString(R.string.search_list_source, name));
                }
            }
            if (!calendarIds.contains(calendarId)) calendarId = -1;
            calendarId = DatedListUi.bindFilter(filter, calendarIds, names, calendarId, selected -> {
                if (binding || filter == null || calendarId == selected) return;
                calendarId = selected;
                request(firstDay, lastDay, currentAnchor(), true);
            });
        } finally {
            binding = false;
        }
    }

    public long getSelectedWritableCalendarId() {
        return writableIds.contains(calendarId) ? calendarId : -1;
    }

    @Override public void onResume() {
        super.onResume();
        refreshStateSymbols.start(list);
        active = true;
        ZoneId latestZone = ZoneId.of(Utils.getTimeZone(requireContext(), null));
        if (!latestZone.equals(zone)) zone = latestZone;
        reloadCalendars();
        Anchor anchor = pendingAnchor != null ? pendingAnchor : currentAnchor();
        request(firstDay, lastDay, anchor, false);
    }

    @Override public void onPause() {
        refreshStateSymbols.stop();
        if (pendingAnchor == null) pendingAnchor = currentAnchor();
        active = false;
        main.removeCallbacks(reload);
        cancelQuery();
        super.onPause();
    }

    @Override public void onDestroyView() {
        refreshStateSymbols.stop();
        active = false;
        main.removeCallbacksAndMessages(null);
        cancelQuery();
        if (loader != null) loader.close();
        loader = null;
        list = null;
        filter = null;
        status = null;
        searchRange = null;
        adapter = null;
        rows.clear();
        super.onDestroyView();
    }

    private void cancelQuery() {
        if (loader != null) loader.cancel();
        loading = false;
    }

    private Anchor currentAnchor() {
        if (list != null && !rows.isEmpty()) {
            int index = Math.min(list.getFirstVisiblePosition(), rows.size() - 1);
            Row row = rows.get(index);
            AgendaEntry event = row.event;
            LocalDate day = row.day == null ? (targetDay == null ? firstDay : targetDay) : row.day;
            // Long.MIN_VALUE identifies the undated section header in saved scroll state.
            long instance = row.task != null ? -row.task.id - 1
                    : row.day == null ? Long.MIN_VALUE : event == null ? -1 : event.instanceId;
            return new Anchor(day, instance, event == null ? -1 : event.id,
                    event == null ? -1 : event.begin, DatedListUi.topOffset(list));
        }
        if (pendingAnchor != null) return pendingAnchor;
        LocalDate anchor = targetDay == null ? LocalDate.now(zone == null ? ZoneId.systemDefault() : zone) : targetDay;
        return new Anchor(anchor, -1, -1, -1, 0);
    }

    public Bundle saveViewState() {
        Bundle state = new Bundle();
        Anchor anchor = pendingAnchor != null ? pendingAnchor : currentAnchor();
        ZoneId savedZone = zone == null ? ZoneId.systemDefault() : zone;
        state.putLong("anchor_time", anchor.day.atStartOfDay(savedZone).toInstant().toEpochMilli());
        state.putLong("instance", anchor.instance);
        state.putLong("event_id", anchor.eventId);
        state.putLong("begin", anchor.begin);
        state.putInt("offset", anchor.offset);
        state.putLong("calendar_id", calendarId);
        if (searchQuery != null) state.putString("search_query", searchQuery);
        if (firstDay != null) state.putLong("first_day", firstDay.toEpochDay());
        if (lastDay != null) state.putLong("last_day", lastDay.toEpochDay());
        return state;
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putAll(saveViewState());
    }

    private void goTo(LocalDate day) {
        if (list == null) return;
        targetDay = day;
        Anchor anchor = new Anchor(day, -1, -1, -1, 0);
        if (!active) {
            pendingAnchor = anchor;
            if (day.isBefore(firstDay) || day.isAfter(lastDay)) {
                firstDay = day.minusDays(BLOCK_DAYS);
                lastDay = day.plusDays(BLOCK_DAYS);
            }
            return;
        }
        if (hasLoaded && !loading && loadedFirstDay != null && loadedLastDay != null
                && !day.isBefore(loadedFirstDay) && !day.isAfter(loadedLastDay)) {
            restoreAnchor(anchor);
        } else {
            request(day.minusDays(BLOCK_DAYS), day.plusDays(BLOCK_DAYS), anchor, false);
        }
    }

    private void request(LocalDate first, LocalDate last, Anchor anchor, boolean clear) {
        if (list == null || loader == null) return;
        if (!active) {
            firstDay = first;
            lastDay = last;
            pendingAnchor = anchor;
            return;
        }
        main.removeCallbacks(reload);
        cancelQuery();
        pendingAnchor = anchor;
        firstDay = first;
        lastDay = last;
        updateSearchRange();
        loading = true;
        if (clear) {
            binding = true;
            rows.clear();
            adapter.notifyDataSetChanged();
            binding = false;
            hasLoaded = false;
            loadedFirstDay = loadedLastDay = null;
        }
        if (rows.isEmpty()) {
            status.setText(R.string.loading);
            status.setVisibility(View.VISIBLE);
        }
        Context context = requireContext().getApplicationContext();
        long selected = calendarId;
        ZoneId queryZone = zone;
        LocalDate today = LocalDate.now(queryZone);
        boolean hideDeclined = GeneralPreferences.Companion.getSharedPreferences(context)
                .getBoolean(GeneralPreferences.KEY_HIDE_DECLINED, false);
        String queryText = searchQuery;
        loader.request(0, signal -> {
            List<Row> result = query(context, first, last, selected, hideDeclined, today, queryText, signal);
            if (queryText == null && result.stream().noneMatch(row -> row.event != null)
                    && result.stream().noneMatch(row -> row.day.equals(anchor.day))) {
                result.add(new Row(anchor.day, null));
                result.sort(Comparator.comparing(row -> row.day));
            }
            return result;
        }, result -> {
            if (!active || list == null) return;
            loading = false;
            firstDay = first;
            lastDay = last;
            hasLoaded = true;
            loadedFirstDay = first;
            loadedLastDay = last;
            binding = true;
            rows.clear();
            rows.addAll(result);
            adapter.notifyDataSetChanged();
            if (result.stream().noneMatch(Row::isItem)) {
                status.setText(queryText == null ? R.string.agenda_no_events_in_range
                        : queryText.isEmpty() ? R.string.agenda_search_enter_query
                        : R.string.agenda_search_no_results);
                status.setVisibility(View.VISIBLE);
            } else status.setVisibility(View.GONE);
            restoreAnchor(anchor);
            pendingAnchor = null;
            binding = false;
        }, error -> {
            if (!active || list == null) return;
            Log.w(TAG, "Could not query agenda instances", error);
            loading = false;
            if (loadedFirstDay != null && loadedLastDay != null) {
                firstDay = loadedFirstDay;
                lastDay = loadedLastDay;
                updateSearchRange();
            }
            pendingAnchor = null;
            status.setText(Utils.isCalendarPermissionGranted(requireContext(), false)
                    ? R.string.agenda_load_failed : R.string.calendar_permission_not_granted);
            status.setVisibility(View.VISIBLE);
        });
    }

    private static List<Row> query(Context context, LocalDate first, LocalDate last,
            long selected, boolean hideDeclined, LocalDate today, String searchQuery, CancellationSignal signal) {
        ArrayList<Row> result = new ArrayList<>();
        if (searchQuery == null) {
            List<DatedItemsRepository.Item<AgendaEntry>> items =
                    DatedItemsRepository.agenda(context, first, last, selected, hideDeclined, signal);
            DatedListUi.appendDays(result, items,
                    today.isBefore(first) || today.isAfter(last) ? null : today,
                    date -> new Row(date, null), item -> new Row(item.day, item.value));
            return result;
        }
        if (searchQuery.trim().isEmpty()) return result;

        List<DatedItemsRepository.Item<Row>> dated = new ArrayList<>();
        List<Row> undated = new ArrayList<>();
        if (selected >= -1 && Utils.isCalendarPermissionGranted(context, false)) {
            for (DatedItemsRepository.Item<AgendaEntry> item :
                    DatedItemsRepository.agenda(context, first, last, selected, hideDeclined, searchQuery, signal)) {
                dated.add(new DatedItemsRepository.Item<>(item.day, new Row(item.day, item.value)));
            }
        }
        if (selected < 0) {
            long listId = selected == -1 ? 0 : -selected - 1;
            for (DatedItemsRepository.Item<TaskStore.Task> item :
                    DatedItemsRepository.searchTasks(context, first, last, listId, searchQuery, signal)) {
                Row row = new Row(item.day, null, item.value);
                if (item.day == null) undated.add(row);
                else dated.add(new DatedItemsRepository.Item<>(item.day, row));
            }
        }
        dated.sort(Comparator.comparing((DatedItemsRepository.Item<Row> item) -> item.day)
                .thenComparing(item -> !item.value.allDay())
                .thenComparingLong(item -> item.value.sortTime()));
        DatedListUi.appendDays(result, dated, null,
                day -> new Row(day, null), item -> item.value);
        if (!undated.isEmpty()) {
            result.add(new Row(null, null));
            result.addAll(undated);
        }
        return result;
    }

    private void restoreAnchor(Anchor anchor) {
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.day == null && !row.isItem() && anchor.instance == Long.MIN_VALUE) {
                index = i;
                break;
            }
            if (row.task != null && anchor.instance == -row.task.id - 1) {
                index = i;
                break;
            }
            if (row.day == null || !row.day.equals(anchor.day)) continue;
            if (!row.isItem() && anchor.instance == -1 && anchor.eventId < 0) {
                index = i;
                break;
            }
            if (row.event != null && (row.event.instanceId == anchor.instance
                    || (row.event.id == anchor.eventId && row.event.begin == anchor.begin))) {
                index = i;
                break;
            }
        }
        boolean exact = index >= 0;
        if (searchQuery == null && index < 0 && rows.stream().noneMatch(row -> row.event != null)) {
            // No event is available to jump to; do not change the requested date.
            binding = true;
            rows.add(new Row(anchor.day, null));
            rows.sort(Comparator.comparing(row -> row.day));
            adapter.notifyDataSetChanged();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).day.equals(anchor.day)) { index = i; break; }
            }
            binding = false;
        }
        if (index < 0) index = DatedListUi.findHeader(rows, anchor.day,
                row -> !row.isItem() ? row.day : null, row -> row.day == null);
        if (index < 0 && searchQuery != null && !rows.isEmpty()) index = 0;
        if (index >= 0) list.setSelectionFromTop(index, exact ? anchor.offset : 0);
        targetDay = index >= 0 && rows.get(index).day != null ? rows.get(index).day : anchor.day;
        publishDay(targetDay);
    }

    private void updateScrolledTitle(int first) {
        if (binding || first < 0 || first >= rows.size() || !isAdded()) return;
        if (rows.get(first).day != null) publishDay(rows.get(first).day);
    }

    private void publishDay(LocalDate day) {
        targetDay = day;
        if (searchQuery != null) return;
        if (day.equals(publishedDay)) return;
        publishedDay = day;
        main.post(() -> {
            if (!active || list == null || !day.equals(publishedDay)
                    || controller.getViewType() != ViewType.AGENDA) return;
            DatedListUi.publishDate(controller, this, zone, day);
        });
    }

    @Override public long getSupportedEventTypes() { return EventType.GO_TO | EventType.EVENTS_CHANGED; }

    @Override public void handleEvent(EventInfo event) {
        if (event.eventType == EventType.GO_TO && event.startTime != null) {
            if (list == null) {
                pendingGoToMillis = event.startTime.toMillis();
                return;
            }
            ZoneId currentZone = zone;
            LocalDate target = Instant.ofEpochMilli(event.startTime.toMillis()).atZone(currentZone).toLocalDate();
            // The activity sends its initialization GO_TO before the fragment is attached.
            // See below for that case; preserve the saved pixel/instance anchor on equal dates.
            if (list != null && pendingAnchor != null && pendingAnchor.day.equals(target) && !hasLoaded) return;
            goTo(target);
        } else if (event.eventType == EventType.EVENTS_CHANGED) eventsChanged();
    }

    @Override public void eventsChanged() {
        if (!active || list == null) return;
        main.removeCallbacks(reload);
        main.post(reload);
    }

    /** Main agenda rows mirror the dated reminders' layout and theme palette.
     */
    private final class AgendaRowView {
        final LinearLayout root;
        final TextView chip;
        final TextView title, when, where, countdown;

        AgendaRowView(Context context) {
            DatedListUi.RowLayout layout = new DatedListUi.RowLayout(context);
            root = layout.root;

            chip = DatedListUi.RowLayout.marker(context);
            layout.addLeading(chip);
            title = layout.addTitle();
            when = layout.addDetail();
            where = layout.addDetail();
            countdown = layout.addCountdown();
            root.setTag(this);
        }

        void bind(Row row, Context context, LocalDate today) {
            if (row.task != null) {
                bindTask(row.task, context);
                return;
            }
            AgendaEntry entry = row.event;
            when.setVisibility(View.VISIBLE);
            boolean past = entry.allDay ? !row.day.isAfter(today)
                    : Math.max(entry.begin, millis(row.day)) <= System.currentTimeMillis();
            com.android.calendar.ItemAppearance.bindRow(root,
                    Utils.getDisplayColorFromColor(context, entry.color),
                    title, countdown, when, where, chip);
            ItemAppearance.bindTitle(title,
                    TextUtils.isEmpty(entry.title) ? context.getString(R.string.no_title_label) : entry.title,
                    !past, entry.status == Events.STATUS_CANCELED);

            chip.setText(ItemStateSymbols.event(entry.begin, entry.end, entry.allDay,
                    zone, System.currentTimeMillis()));

            String tz = entry.allDay ? "UTC" : zone.getId();
            int flags = entry.allDay ? 0 : DateUtils.FORMAT_SHOW_TIME;
            if (DateFormat.is24HourFormat(context)) flags |= DateUtils.FORMAT_24HOUR;
            String time = DateUtils.formatDateRange(context, new java.util.Formatter(),
                    entry.begin, entry.end, flags, tz).toString();
            if (!entry.allDay && !TextUtils.equals(tz, entry.timezone)) {
                TimeZone displayZone = TimeZone.getTimeZone(tz);
                time += " (" + (displayZone.getID().equals("GMT") ? tz
                        : displayZone.getDisplayName(false, TimeZone.SHORT)) + ")";
            }
            when.setText(time);
            DatedListUi.bindOptionalText(where, entry.location);
            DatedListUi.bindOptionalText(countdown,
                    EventCountdown.label(context, entry.id, entry.begin, entry.allDay));
        }

        private void bindTask(TaskStore.Task task, Context context) {
            long now = System.currentTimeMillis();
            TaskListUi.bindPresentation(root, chip, title, task, zone, now, countdown, when, where);
            DatedListUi.bindOptionalText(when, TaskListUi.dueLabel(context, task, zone, now));
            DatedListUi.bindOptionalText(where, task.notes);
            DatedListUi.bindOptionalText(countdown, TaskListUi.countdownLabel(context, task));
        }
    }

    private final class AgendaRows extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int position) {
            Row row = rows.get(position);
            return row.isItem() ? 1 : row.day == null ? 2 : 0;
        }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return rows.get(position).isItem(); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            Context context = parent.getContext();
            LocalDate today = LocalDate.now(zone);
            if (!row.isItem()) {
                if (row.day != null) return DatedListUi.dateHeader(convertView, parent, row.day, zone);
                TextView heading = DatedListUi.statusView(context, convertView, true);
                heading.setText(R.string.tasks_title);
                return heading;
            }

            AgendaRowView holder = convertView != null && convertView.getTag() instanceof AgendaRowView
                    ? (AgendaRowView) convertView.getTag() : new AgendaRowView(context);
            holder.bind(row, context, today);
            return holder.root;
        }
    }
}
