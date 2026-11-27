package com.android.calendar;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.android.calendar.calendarcommon2.Time;
import com.android.calendar.theme.DynamicThemeKt;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import ws.xsoh.etar.R;

/** Common layout, date grouping, scroll and date positioning for dated lists. */
public final class DatedListUi {
    private DatedListUi() {}
    public static int dp(Context context, int n) {
        return Math.round(n * context.getResources().getDisplayMetrics().density);
    }
    public static ListView createList(Context context, BaseAdapter adapter) {
        ListView list = new ListView(context);
        list.setDivider(null);
        list.setPadding(0, 0, 0, dp(context, 80));
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        list.setItemsCanFocus(true);
        return list;
    }
    public static void listenToScroll(ListView list, IntConsumer callback) {
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            private int state = SCROLL_STATE_IDLE;
            @Override public void onScrollStateChanged(AbsListView view, int next) {
                if (state != SCROLL_STATE_IDLE && next == SCROLL_STATE_IDLE)
                    callback.accept(view.getFirstVisiblePosition());
                state = next;
            }
            @Override public void onScroll(AbsListView view, int first, int count, int total) {
                if (state != SCROLL_STATE_IDLE) callback.accept(first);
            }
        });
    }
    public static int topOffset(ListView list) {
        return list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop();
    }
    public static <R> int findHeader(List<R> rows, LocalDate target,
            Function<R, LocalDate> headerDay, Predicate<R> stop) {
        int last = -1;
        for (int i = 0; i < rows.size(); i++) {
            R row = rows.get(i);
            if (stop.test(row)) break;
            LocalDate day = headerDay.apply(row);
            if (day == null) continue;
            last = i;
            if (!day.isBefore(target)) return i;
        }
        return last;
    }
    /** Input is sorted by display date; null today disables insertion of an empty today header. */
    public static <T, R> void appendDays(List<R> rows, List<DatedItemsRepository.Item<T>> items,
            LocalDate today, Function<LocalDate, R> header,
            Function<DatedItemsRepository.Item<T>, R> item) {
        LocalDate previous = null;
        boolean todayAdded = today == null;
        for (DatedItemsRepository.Item<T> value : items) {
            LocalDate date = value.day;
            if (!date.equals(previous)) {
                if (!todayAdded && date.isAfter(today)) {
                    rows.add(header.apply(today));
                    todayAdded = true;
                }
                rows.add(header.apply(date));
                if (date.equals(today)) todayAdded = true;
                previous = date;
            }
            rows.add(item.apply(value));
        }
        if (!todayAdded) rows.add(header.apply(today));
    }
    public static void publishDate(CalendarController controller, Object sender, ZoneId zone, LocalDate day) {
        Time time = new Time(zone.getId());
        time.set(day.atStartOfDay(zone).toInstant().toEpochMilli());
        controller.setTime(time.toMillis());
        controller.sendEvent(sender, CalendarController.EventType.UPDATE_TITLE, time, time, null,
                -1, CalendarController.ViewType.CURRENT, 0, null, null);
    }
    public static View dateHeader(View recycled, ViewGroup parent, LocalDate day, ZoneId zone) {
        Context context = parent.getContext();
        View header = recycled == null
                ? LayoutInflater.from(context).inflate(R.layout.agenda_day, parent, false) : recycled;
        LocalDate today = LocalDate.now(zone);
        long millis = day.atStartOfDay(zone).toInstant().toEpochMilli();
        ((TextView) header.findViewById(R.id.day)).setText(Utils.getDayOfWeekString(
                (int) (day.toEpochDay() + 2440588L), (int) (today.toEpochDay() + 2440588L), millis, context));
        ((TextView) header.findViewById(R.id.date)).setText(
                Utils.formatDateRange(context, millis, millis, DateUtils.FORMAT_SHOW_DATE));
        header.setBackgroundResource(DynamicThemeKt.getDrawableId(context,
                day.isAfter(today) ? "agenda_item_bg_primary" : "agenda_item_bg_secondary"));
        header.findViewById(R.id.top_divider_past_present).setVisibility(View.GONE);
        return header;
    }
    public static final class RowLayout {
        public final LinearLayout root, item, details;
        public RowLayout(Context context) {
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setMinimumHeight(dp(context, 64));
            View divider = new View(context);
            divider.setBackgroundResource(R.color.agenda_list_separator_color);
            root.addView(divider, new LinearLayout.LayoutParams(-1, 1));
            item = new LinearLayout(context);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinimumHeight(dp(context, 64) - 1);
            item.setPadding(0, 0, dp(context, 16), 0);
            root.addView(item, new LinearLayout.LayoutParams(-1, -2));
            details = new LinearLayout(context);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(0, dp(context, 8), 0, dp(context, 8));
        }
        public void addLeading(View view) {
            int size = dp(root.getContext(), 48);
            item.addView(view, new LinearLayout.LayoutParams(size, size));
            item.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        }
        public TextView addText(int size) {
            TextView view = textView(root.getContext(), size);
            details.addView(view);
            return view;
        }
        public static TextView textView(Context context, int size) {
            TextView view = new TextView(context);
            view.setTextSize(size);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            return view;
        }
    }
}
