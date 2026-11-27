package com.android.calendar;

import android.content.Context;
import android.os.Handler;
import android.text.format.DateUtils;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import android.view.LayoutInflater;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.android.calendar.calendarcommon2.Time;

import java.util.Formatter;
import java.util.Locale;

import ws.xsoh.etar.R;
import com.android.calendar.settings.GeneralPreferences;

/**
 * Created by xsoh64 on 7/21/15.
 */
public class CalendarToolbarHandler {

    private final LayoutInflater mInflater;
    private final StringBuilder mStringBuilder;
    private final Formatter mFormatter;
    private AppCompatActivity mContext;
    private Toolbar mToolbar;
    private int mCurrentViewType;
    // The current selected event's time, used to calculate the date and day of the week
    // for the buttons.
    private long mMilliTime;
    private long mVisibleStart = Long.MIN_VALUE;
    private long mVisibleEnd = Long.MIN_VALUE;
    private String mTimeZone;
    private Handler mMidnightHandler = null; // Used to run a time update every midnight

    private final Runnable mTimeUpdater = new Runnable() {
        @Override
        public void run() {
            refresh(mContext);
        }
    };


    public CalendarToolbarHandler(AppCompatActivity context, Toolbar toolbar, int defaultViewType) {
        mContext = context;
        mToolbar = toolbar;
        mCurrentViewType = defaultViewType;

        mMidnightHandler = new Handler();
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mStringBuilder = new StringBuilder(50);
        mFormatter = new Formatter(mStringBuilder, Locale.getDefault());

        refresh(mContext);
    }

    // Sets the time zone and today's Julian day to be used by the adapter.
    // Also, update the change and resets the midnight update thread.
    public void refresh(Context context) {
        mTimeZone = Utils.getTimeZone(context, mTimeUpdater);
        updateTitle();
        setMidnightHandler();
    }

    public void setCurrentMainView(int viewType) {
        mCurrentViewType = viewType;
        mVisibleStart = mVisibleEnd = Long.MIN_VALUE;
        updateTitle();
    }

    // Update the date that is displayed on buttons
    // Used when the user selects a new day/week/month to watch
    public void setTime(long time) {
        setTime(time, Long.MIN_VALUE, Long.MIN_VALUE);
    }

    public void setTime(long time, long visibleStart, long visibleEnd) {
        mMilliTime = time;
        mVisibleStart = visibleStart;
        mVisibleEnd = visibleEnd;
        updateTitle();
    }

    /** Day/agenda place the short weekday next to the Gregorian date. */
    private void updateTitle() {
        String secondary;
        switch (mCurrentViewType) {
            case CalendarController.ViewType.REMINDERS:
                mToolbar.setTitle(R.string.tasks_title);
                mToolbar.setSubtitle(null);
                return;
            case CalendarController.ViewType.DAY:
            case CalendarController.ViewType.AGENDA:
                mToolbar.setTitle(appendShortWeekday(mContext, buildFullDate(), mMilliTime, mTimeZone));
                secondary = "";
                break;
            case CalendarController.ViewType.WEEK:
                mToolbar.setTitle(buildMonthYearDate());
                secondary = Utils.getShowWeekNumber(mContext) ? buildWeekNum() : "";
                break;
            case CalendarController.ViewType.MONTH:
                mToolbar.setTitle(buildMonthYearDate());
                secondary = "";
                break;
            default:
                return;
        }
        String lunar = buildLunarTitle();
        String subtitle = lunar.isEmpty() ? secondary
                : secondary.isEmpty() ? lunar : lunar + "\n" + secondary;
        mToolbar.setSubtitle(subtitle);
        // AppCompat creates its subtitle TextView lazily and defaults to single-line.
        // Keep the native toolbar title, theme, insets and menu handling.
        for (int i = 0; i < mToolbar.getChildCount(); i++) {
            View child = mToolbar.getChildAt(i);
            if (child instanceof TextView && TextUtils.equals(((TextView) child).getText(), subtitle)) {
                TextView text = (TextView) child;
                boolean multiline = !lunar.isEmpty() && !secondary.isEmpty();
                text.setSingleLine(!multiline);
                text.setMaxLines(multiline ? 2 : 1);
                text.setEllipsize(TextUtils.TruncateAt.END);
                break;
            }
        }
    }

    private String buildLunarTitle() {
        if (!LocalizationSettings.lunar(mContext)) return "";
        ZoneId zone;
        try {
            zone = ZoneId.of(mTimeZone);
        } catch (RuntimeException e) {
            zone = ZoneId.systemDefault();
        }
        LocalDate reference = Instant.ofEpochMilli(mMilliTime).atZone(zone).toLocalDate();
        LocalDate start = reference;
        LocalDate end = start;
        boolean monthOnly = mCurrentViewType == CalendarController.ViewType.MONTH
                || mCurrentViewType == CalendarController.ViewType.WEEK;
        if (mCurrentViewType == CalendarController.ViewType.MONTH) {
            start = start.withDayOfMonth(1);
            end = start.withDayOfMonth(start.lengthOfMonth());
        } else if (mCurrentViewType == CalendarController.ViewType.WEEK) {
            if (mVisibleStart != Long.MIN_VALUE && mVisibleEnd > mVisibleStart) {
                start = Instant.ofEpochMilli(mVisibleStart).atZone(zone).toLocalDate();
                end = Instant.ofEpochMilli(mVisibleEnd).atZone(zone).toLocalDate();
            } else {
                int count = Utils.getDaysPerWeek(mContext);
                if (count == 7) {
                    int first = Utils.getFirstDayOfWeek(mContext);
                    start = start.with(TemporalAdjusters.previousOrSame(
                            first == 0 ? DayOfWeek.SUNDAY : DayOfWeek.of(first)));
                }
                end = start.plusDays(count - 1L);
            }
        }
        return LunarUtils.header(mContext, reference, start, end, LocalDate.now(zone), monthOnly);
    }

    // Sets a thread to run 1 second after midnight and update the current date
    // This is used to display correctly the date of yesterday/today/tomorrow
    private void setMidnightHandler() {
        mMidnightHandler.removeCallbacks(mTimeUpdater);
        // Set the time updater to run at 1 second after midnight
        long now = System.currentTimeMillis();
        Time time = new Time(mTimeZone);
        time.set(now);
        long runInMillis = (24 * 3600 - time.getHour() * 3600L - time.getMinute() * 60L -
                time.getSecond() + 1) * 1000;
        mMidnightHandler.postDelayed(mTimeUpdater, runInMillis);
    }

    /** Use the locale's short weekday without relative-day prefixes (today/yesterday). */
    public static CharSequence appendShortWeekday(Context context, String date, long millis, String timeZone) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone);
        } catch (RuntimeException e) {
            zone = ZoneId.systemDefault();
        }
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String weekday = Instant.ofEpochMilli(millis).atZone(zone)
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE", locale));
        SpannableString title = new SpannableString(date + " " + weekday);
        title.setSpan(new RelativeSizeSpan(0.75f), date.length() + 1, title.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return title;
    }

    // Builds strings with different formats:
    // Full date: Month,day Year
    // Month year
    // Month day
    // Month
    // Week:  month day-day or month day - month day
    private String buildFullDate() {
        mStringBuilder.setLength(0);
        String date = DateUtils.formatDateRange(mContext, mFormatter, mMilliTime, mMilliTime,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | abbrevMonthFlag(),
                mTimeZone).toString();
        return date;
    }

    /**
     * With the view buttons in the toolbar, the month is abbreviated (ie. "23 Sept 2026") so that
     * the title still fits next to them
     */
    private int abbrevMonthFlag() {
        return Utils.getSharedPreference(mContext, GeneralPreferences.KEY_VIEW_SWITCH_BUTTONS, false)
                ? DateUtils.FORMAT_ABBREV_MONTH : 0;
    }

    private String buildMonthYearDate() {
        mStringBuilder.setLength(0);
        String date = DateUtils.formatDateRange(
                mContext,
                mFormatter,
                mMilliTime,
                mMilliTime,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_MONTH_DAY
                        | DateUtils.FORMAT_SHOW_YEAR | abbrevMonthFlag(), mTimeZone).toString();
        return date;
    }

    private String buildMonthDayDate() {
        mStringBuilder.setLength(0);
        String date = DateUtils.formatDateRange(mContext, mFormatter, mMilliTime, mMilliTime,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR, mTimeZone).toString();
        return date;
    }

    private String buildMonthDate() {
        mStringBuilder.setLength(0);
        String date = DateUtils.formatDateRange(
                mContext,
                mFormatter,
                mMilliTime,
                mMilliTime,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR
                        | DateUtils.FORMAT_NO_MONTH_DAY, mTimeZone).toString();
        return date;
    }

    private String buildWeekDate() {


        // Calculate the start of the week, taking into account the "first day of the week"
        // setting.

        Time t = new Time(mTimeZone);
        t.set(mMilliTime);
        int firstDayOfWeek = Utils.getFirstDayOfWeek(mContext);
        int dayOfWeek = t.getWeekDay();
        int diff = dayOfWeek - firstDayOfWeek;
        if (diff != 0) {
            if (diff < 0) {
                diff += 7;
            }
            t.setDay(t.getDay() - diff);
            t.normalize();
        }

        long weekStartTime = t.toMillis();
        // The end of the week is 6 days after the start of the week
        long weekEndTime = weekStartTime + DateUtils.WEEK_IN_MILLIS - DateUtils.DAY_IN_MILLIS;

        // If week start and end is in 2 different months, use short months names
        Time t1 = new Time(mTimeZone);
        t.set(weekEndTime);
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR;
        if (t.getMonth() != t1.getMonth()) {
            flags |= DateUtils.FORMAT_ABBREV_MONTH;
        }

        mStringBuilder.setLength(0);
        String date = DateUtils.formatDateRange(mContext, mFormatter, weekStartTime,
                weekEndTime, flags, mTimeZone).toString();
        return date;
    }

    private String buildWeekNum() {
        int week = Utils.getWeekNumberFromTime(mMilliTime, mContext);
        return mContext.getResources().getQuantityString(R.plurals.weekN, week, week);
    }
}
