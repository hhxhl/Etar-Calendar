package com.android.calendar;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ImageView;
import com.android.calendar.calendarcommon2.Time;
import com.android.calendar.theme.DynamicThemeKt;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.function.LongConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import ws.xsoh.etar.R;

/** Common layout, date grouping, scroll and date positioning for dated lists. */
public final class DatedListUi {
    private DatedListUi() {}
    /** Refresh visible time-dependent labels without issuing another database query. */
    public static final class MinuteRefresh implements Runnable {
        private ListView list;

        public void start(ListView view) {
            stop();
            list = view;
            schedule();
        }

        private void schedule() {
            if (list != null) list.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }

        public void stop() {
            if (list != null) list.removeCallbacks(this);
            list = null;
        }

        @Override public void run() {
            if (list == null) return;
            list.invalidateViews();
            schedule();
        }
    }

    public static int dp(Context context, int n) {
        return Math.round(n * context.getResources().getDisplayMetrics().density);
    }
    public static int dimension(Context context, int resource) {
        return context.getResources().getDimensionPixelSize(resource);
    }

    public static void setTextSize(TextView view, int resource) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                view.getResources().getDimension(resource));
    }

    /** Shared neutral status/empty row and optional section heading. */
    public static TextView statusView(Context context, View recycled, boolean section) {
        TextView view = recycled instanceof TextView ? (TextView) recycled : new TextView(context);
        int horizontal = dimension(context, R.dimen.item_list_end_padding);
        int vertical = dimension(context, section ? R.dimen.item_list_section_vertical_padding
                : R.dimen.item_list_status_vertical_padding);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        view.setTextColor(DynamicThemeKt.getColor(context,
                section ? "agenda_item_standard_color" : "agenda_item_where_text_color"));
        view.setBackgroundResource(section ? R.drawable.agenda_item_bg_secondary : R.drawable.agenda_item_bg_primary);
        return view;
    }

    /** Bind a stable source selector without rebuilding an unchanged adapter. */
    public static long bindFilter(Spinner filter, List<Long> ids, List<String> names,
            long selected, LongConsumer onSelected) {
        if (ids.isEmpty() || ids.size() != names.size()) throw new IllegalArgumentException("Invalid sources");
        List<Long> snapshot = new ArrayList<>(ids);
        int position = Math.max(0, snapshot.indexOf(selected));
        boolean unchanged = snapshot.equals(filter.getTag()) && filter.getAdapter() != null
                && filter.getAdapter().getCount() == names.size();
        if (unchanged) {
            for (int i = 0; i < names.size(); i++) {
                if (!names.get(i).equals(filter.getAdapter().getItem(i))) {
                    unchanged = false;
                    break;
                }
            }
        }
        filter.setOnItemSelectedListener(null);
        if (!unchanged) {
            ArrayAdapter<String> labels = new ArrayAdapter<>(filter.getContext(),
                    android.R.layout.simple_spinner_item, new ArrayList<>(names));
            labels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            filter.setAdapter(labels);
            filter.setTag(snapshot);
        }
        if (filter.getSelectedItemPosition() != position) filter.setSelection(position);
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int p, long id) {
                if (p >= 0 && p < snapshot.size() && p == filter.getSelectedItemPosition())
                    onSelected.accept(snapshot.get(p));
            }
        });
        return snapshot.get(position);
    }

    public static void bindOptionalText(TextView view, CharSequence text) {
        ItemAppearance.bindText(view, text);
        view.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    public static ListView createList(Context context, BaseAdapter adapter) {
        ListView list = new ListView(context);
        list.setDivider(null);
        list.setPadding(0, 0, 0, dimension(context, R.dimen.item_list_bottom_padding));
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
        header.setBackgroundResource(day.isAfter(today) ? R.drawable.agenda_item_bg_primary : R.drawable.agenda_item_bg_secondary);
        header.findViewById(R.id.top_divider_past_present).setVisibility(View.GONE);
        return header;
    }
    public static final class RowLayout {
        public final LinearLayout root, item, details;
        public RowLayout(Context context) {
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setMinimumHeight(dimension(context, R.dimen.item_list_min_height));
            View divider = new View(context);
            divider.setBackgroundResource(R.color.agenda_list_separator_color);
            root.addView(divider, new LinearLayout.LayoutParams(-1, 1));
            item = new LinearLayout(context);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinimumHeight(dimension(context, R.dimen.item_list_min_height) - 1);
            item.setPadding(0, 0, dimension(context, R.dimen.item_list_end_padding), 0);
            root.addView(item, new LinearLayout.LayoutParams(-1, -2));
            details = new LinearLayout(context);
            details.setOrientation(LinearLayout.VERTICAL);
            int padding = dimension(context, R.dimen.item_list_detail_padding);
            details.setPadding(0, padding, 0, padding);
        }
        public void addLeading(View view) {
            int size = dimension(root.getContext(), R.dimen.item_list_leading_size);
            item.addView(view, new LinearLayout.LayoutParams(size, size));
            item.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        }
        public TextView addTitle() {
            return addStyledText(R.dimen.item_list_title_size);
        }

        public TextView addDetail() {
            return addStyledText(R.dimen.item_list_detail_size);
        }

        private TextView addStyledText(int resource) {
            TextView view = styledText(root.getContext(), resource);
            details.addView(view);
            return view;
        }

        public TextView addCountdown() {
            TextView view = styledText(root.getContext(), R.dimen.item_list_countdown_size);
            addAccessory(view, -2);
            return view;
        }

        public void addIcon(View view) {
            addAccessory(view, dimension(root.getContext(), R.dimen.item_list_accessory_size));
        }

        private void addAccessory(View view, int size) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginStart(dimension(root.getContext(), R.dimen.item_list_accessory_gap));
            item.addView(view, params);
        }

        public static ImageView marker(Context context) {
            ImageView view = new ImageView(context);
            view.setScaleType(ImageView.ScaleType.CENTER);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return view;
        }

        private static TextView styledText(Context context, int resource) {
            TextView view = new TextView(context);
            setTextSize(view, resource);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            return view;
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
