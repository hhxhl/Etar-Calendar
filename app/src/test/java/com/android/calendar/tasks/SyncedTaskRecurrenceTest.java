package com.android.calendar.tasks;

import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyncedTaskRecurrenceTest {
    private static long ms(String iso) { return Instant.parse(iso).toEpochMilli(); }
    private TaskStore.Task task(String rule) {
        TaskStore.Task task = new TaskStore.Task();
        task.local = false;
        task.start = ms("2027-01-04T09:00:00Z");
        task.due = ms("2027-01-04T10:00:00Z");
        task.tz = "UTC";
        task.rule = rule;
        return task;
    }
    @Test public void fortnightlyUsesStartAndRetainsRule() {
        TaskStore.Task task = task("FREQ=WEEKLY;INTERVAL=2");
        TaskRecurrence.Advance next = TaskRecurrence.next(task);
        assertNotNull(next);
        assertEquals(ms("2027-01-18T09:00:00Z"), next.next);
        assertEquals(task.rule, next.rule);
        assertEquals(Long.valueOf(ms("2027-01-18T10:00:00Z")), TaskRecurrence.shift(task.due, false, task.zone(), next, false));
    }
    @Test public void finiteRuleConsumesOneOccurrence() {
        TaskStore.Task task = task("FREQ=DAILY;COUNT=2");
        TaskRecurrence.Advance next = TaskRecurrence.next(task);
        assertNotNull(next);
        assertEquals("FREQ=DAILY;COUNT=1", next.rule);
        task.rule = next.rule;
        assertNull(TaskRecurrence.next(task));
    }
    @Test public void exceptionMasterIsNotMoved() {
        TaskStore.Task task = task("FREQ=DAILY");
        task.hasRecurrenceExceptions = true;
        assertNull(TaskRecurrence.next(task));
    }
}
