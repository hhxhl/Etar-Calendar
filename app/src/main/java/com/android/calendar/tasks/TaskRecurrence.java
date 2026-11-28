package com.android.calendar.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.Duration;
import com.android.calendar.calendarcommon2.DateException;
import com.android.calendar.calendarcommon2.EventRecurrence;
import com.android.calendar.calendarcommon2.RecurrenceProcessor;
import com.android.calendar.calendarcommon2.RecurrenceSet;
import com.android.calendar.calendarcommon2.Time;

/** Local preset rules and bounded standard recurrence expansion for synced tasks.
 * Moving the current resource forward is not a recurrence-exception implementation.
 */
final class TaskRecurrence {
    private TaskRecurrence() {}

    /** Preset recurrence: preserve original day-of-month and wall-clock time. */
    static Long nextLocal(TaskStore.Task t) {
        Long display = t.displayTime();
        if(display==null || t.rule==null) return null;
        ZoneId zone=t.displayAllDay()?ZoneOffset.UTC:t.zone();
        ZonedDateTime current=Instant.ofEpochMilli(display).atZone(zone);
        ZonedDateTime anchor=Instant.ofEpochMilli(t.anchor==null?display:t.anchor).atZone(zone);
        LocalTime wallTime = t.displayAllDay() ? LocalTime.MIDNIGHT
                : t.repeatMinute != null ? LocalTime.of(t.repeatMinute / 60, t.repeatMinute % 60)
                : anchor.toLocalTime();
        ZonedDateTime next;
        switch(t.rule) {
            case "FREQ=DAILY": next=current.toLocalDate().plusDays(1).atTime(wallTime).atZone(zone); break;
            case "FREQ=WEEKLY": next=current.toLocalDate().plusWeeks(1).atTime(wallTime).atZone(zone); break;
            case "FREQ=MONTHLY":
                LocalDate month=current.toLocalDate().withDayOfMonth(1).plusMonths(1);
                next=month.withDayOfMonth(Math.min(anchor.getDayOfMonth(),month.lengthOfMonth())).atTime(wallTime).atZone(zone); break;
            case "FREQ=YEARLY":
                LocalDate year=anchor.toLocalDate().plusYears((long)current.getYear()-anchor.getYear()+1);
                next=year.atTime(wallTime).atZone(zone); break;
            default: return null;
        }
        return next.toInstant().toEpochMilli();
    }

    static final class Advance {
        final long base, next;
        final String rule;
        Advance(long base, long next, String rule) {
            this.base = base; this.next = next; this.rule = rule;
        }
    }

    static Advance next(TaskStore.Task task) {
        Long display = task.displayTime();
        if (display == null || task.rule == null) return null;
        if (task.local) {
            Long next = nextLocal(task);
            return next == null ? null : new Advance(display, next, task.rule);
        }
        // Do not move a master resource that contains independent dates or exceptions.
        if (task.hasRecurrenceDates || task.hasRecurrenceExceptions || task.rule.indexOf('\n') >= 0) return null;
        Long base = task.start != null ? task.start : display;
        ZoneId zone = task.displayAllDay() ? ZoneOffset.UTC : task.zone();
        try {
            EventRecurrence rule = new EventRecurrence();
            rule.parse(task.rule);
            if (rule.count == 1 || rule.freq < EventRecurrence.DAILY || rule.interval > 100
                    || rule.byhourCount > 0 || rule.byminuteCount > 0 || rule.bysecondCount > 0) return null;
            // Include DTSTART in the expansion so COUNT includes the current occurrence.
            for (int days = 2; days <= 8192; days *= 4) {
                Time start = new Time(zone.getId());
                start.set(base);
                start.setAllDay(task.displayAllDay());
                long end = Instant.ofEpochMilli(base).atZone(zone).plusDays(days).toInstant().toEpochMilli();
                RecurrenceSet set = new RecurrenceSet(task.rule, null, null, null);
                long[] dates = new RecurrenceProcessor().expand(start, set, base, end);
                for (long date : dates) {
                    if (date <= base) continue;
                    String nextRule = rule.count > 0
                            ? task.rule.replaceFirst("(?i)(^|;)COUNT=[0-9]+", "$1COUNT=" + (rule.count - 1))
                            : task.rule;
                    return new Advance(base, date, nextRule);
                }
                if (rule.until != null) {
                    Time until = new Time(zone.getId());
                    until.parse(rule.until);
                    if (until.toMillis() < end) break;
                }
            }
        } catch (DateException | RuntimeException e) {
            android.util.Log.w("TaskRecurrence", "Unsupported recurrence", e);
        }
        return null;
    }

    static Long shift(Long value, boolean dateOnly, ZoneId zone, Advance advance, boolean baseDateOnly) {
        if (value == null) return null;
        if (value == advance.base && dateOnly == baseDateOnly) return advance.next;
        ZoneId baseZone = baseDateOnly ? ZoneOffset.UTC : zone;
        ZonedDateTime from = Instant.ofEpochMilli(advance.base).atZone(baseZone);
        ZonedDateTime to = Instant.ofEpochMilli(advance.next).atZone(baseZone);
        long days = java.time.temporal.ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate());
        Duration clock = Duration.between(from.toLocalTime(), to.toLocalTime());
        ZonedDateTime original = Instant.ofEpochMilli(value).atZone(dateOnly ? ZoneOffset.UTC : zone);
        return original.toLocalDateTime().plusDays(days).plus(dateOnly ? Duration.ZERO : clock)
                .atZone(original.getZone()).toInstant().toEpochMilli();
    }
}
