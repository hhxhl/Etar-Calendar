package com.android.calendar.tasks;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import java.time.Instant;

public class TaskRecurrenceTest {
    private TaskStore.Task task(String due,String anchor,String rule){
        TaskStore.Task t=new TaskStore.Task();t.due=Instant.parse(due).toEpochMilli();t.anchor=Instant.parse(anchor).toEpochMilli();t.rule=rule;t.allDay=true;return t;
    }
    @Test public void monthlyRetainsOriginalDay(){
        assertEquals(Long.valueOf(Instant.parse("2027-03-31T00:00:00Z").toEpochMilli()),
            TaskStore.nextDue(task("2027-02-28T00:00:00Z","2027-01-31T00:00:00Z","FREQ=MONTHLY")));
    }
    @Test public void dailyRestoresWallTimeAfterDstGap(){
        TaskStore.Task t=task("2027-03-14T07:30:00Z","2027-03-13T07:30:00Z","FREQ=DAILY");
        t.allDay=false;t.tz="America/New_York";t.repeatMinute=150;
        assertEquals(Long.valueOf(Instant.parse("2027-03-15T06:30:00Z").toEpochMilli()),TaskStore.nextDue(t));
    }
    @Test public void weeklyRestoresWallTimeAfterDstGap(){
        TaskStore.Task t=task("2027-03-14T07:30:00Z","2027-03-07T07:30:00Z","FREQ=WEEKLY");
        t.allDay=false;t.tz="America/New_York";t.repeatMinute=150;
        assertEquals(Long.valueOf(Instant.parse("2027-03-21T06:30:00Z").toEpochMilli()),TaskStore.nextDue(t));
    }
    @Test public void yearlyRetainsLeapDay(){
        assertEquals(Long.valueOf(Instant.parse("2028-02-29T00:00:00Z").toEpochMilli()),
            TaskStore.nextDue(task("2027-02-28T00:00:00Z","2024-02-29T00:00:00Z","FREQ=YEARLY")));
    }
    private static long ms(String iso) { return Instant.parse(iso).toEpochMilli(); }

    @Test public void absoluteAlarmDoesNotRequireStartOrDeadline() {
        TaskStore.Task t = new TaskStore.Task();
        t.alarm = true;
        t.alarmMode = TaskStore.Task.ALARM_ABSOLUTE;
        t.alarmAbsolute = ms("2027-01-04T09:00:00Z");
        assertEquals(t.alarmAbsolute, t.alarmTime());
        assertEquals(t.alarmAbsolute, t.displayTime());
        t.due = ms("2027-01-05T09:00:00Z");
        assertEquals(t.alarmAbsolute, t.alarmTime());
        assertEquals(t.due, t.displayTime());
        t.alarm = false;
        org.junit.Assert.assertNull(t.alarmTime());
    }

    @Test public void relativeAlarmFollowsItsSelectedBase() {
        TaskStore.Task t = new TaskStore.Task();
        t.start = ms("2027-01-01T09:00:00Z");
        t.due = ms("2027-01-05T18:00:00Z");
        t.alarm = true;
        t.alarmMode = TaskStore.Task.ALARM_START;
        t.alarmOffset = 30;
        assertEquals(Long.valueOf(ms("2027-01-01T08:30:00Z")), t.alarmTime());
        t.start = ms("2027-01-02T09:00:00Z");
        assertEquals(Long.valueOf(ms("2027-01-02T08:30:00Z")), t.alarmTime());
        t.alarmMode = TaskStore.Task.ALARM_DUE;
        t.alarmOffset = 1440;
        assertEquals(Long.valueOf(ms("2027-01-04T18:00:00Z")), t.alarmTime());
        t.due = null;
        org.junit.Assert.assertNull(t.alarmTime());
    }

    @Test public void dateOnlyAlarmUsesItemTimeZone() {
        TaskStore.Task t = new TaskStore.Task();
        t.due = ms("2027-01-05T00:00:00Z");
        t.allDay = true;
        t.tz = "Asia/Tokyo";
        t.alarm = true;
        t.alarmMode = TaskStore.Task.ALARM_DUE;
        assertEquals(Long.valueOf(ms("2027-01-04T15:00:00Z")), t.alarmTime());
        org.junit.Assert.assertTrue(t.displayAllDay());
    }

    @Test public void startOnlyAndAlarmOnlyCanRepeat() {
        TaskStore.Task t = new TaskStore.Task();
        t.start = ms("2027-01-01T09:00:00Z");
        t.rule = "FREQ=DAILY";
        t.tz = "UTC";
        assertEquals(Long.valueOf(ms("2027-01-02T09:00:00Z")), TaskStore.nextDue(t));
        t.start = null;
        t.alarm = true;
        t.alarmMode = TaskStore.Task.ALARM_ABSOLUTE;
        t.alarmAbsolute = ms("2027-01-04T09:00:00Z");
        assertEquals(Long.valueOf(ms("2027-01-05T09:00:00Z")), TaskStore.nextDue(t));
    }
    @Test public void timedRangePreservesBothEndpoints() {
        TaskStore.Task t = new TaskStore.Task();
        t.start = ms("2027-01-01T09:00:00Z");
        t.due = ms("2027-01-05T18:00:00Z");
        java.time.ZoneId zone = java.time.ZoneId.of("UTC");
        org.junit.Assert.assertTrue(t.hasRange());
        org.junit.Assert.assertFalse(t.rangeAllDay());
        assertEquals(t.start.longValue(), t.rangeStart(zone));
        assertEquals(t.due.longValue(), t.rangeEnd(zone));
        assertEquals(t.due, t.displayTime());
    }

    @Test public void allDayRangeIncludesDeadlineDate() {
        TaskStore.Task t = new TaskStore.Task();
        t.start = ms("2027-01-01T00:00:00Z");
        t.due = ms("2027-01-05T00:00:00Z");
        t.allDay = true;
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Tokyo");
        org.junit.Assert.assertTrue(t.rangeAllDay());
        assertEquals(t.start.longValue(), t.rangeStart(zone));
        assertEquals(ms("2027-01-06T00:00:00Z"), t.rangeEnd(zone));
    }

    @Test public void allDayStartAlarmUsesSameDateFlag() {
        TaskStore.Task t = new TaskStore.Task();
        t.start = ms("2027-01-01T00:00:00Z");
        t.due = ms("2027-01-02T00:00:00Z");
        t.allDay = true;
        t.tz = "Asia/Tokyo";
        t.alarm = true;
        t.alarmMode = TaskStore.Task.ALARM_START;
        assertEquals(Long.valueOf(ms("2026-12-31T15:00:00Z")), t.alarmTime());
    }

    @Test public void startOnlyAllDayUsesDate() {
        TaskStore.Task t = new TaskStore.Task();
        t.start = ms("2027-01-01T00:00:00Z");
        t.allDay = true;
        org.junit.Assert.assertTrue(t.displayAllDay());
        assertEquals(t.start, t.displayTime());
    }

    @Test public void deadlineOnlyRemainsPoint() {
        TaskStore.Task t = new TaskStore.Task();
        t.due = ms("2027-01-05T09:00:00Z");
        java.time.ZoneId zone = java.time.ZoneId.of("UTC");
        org.junit.Assert.assertFalse(t.hasRange());
        assertEquals(t.due.longValue(), t.rangeStart(zone));
        assertEquals(t.due + 60000L, t.rangeEnd(zone));
    }
}
