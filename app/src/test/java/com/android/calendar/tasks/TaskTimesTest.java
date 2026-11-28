package com.android.calendar.tasks;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskTimesTest {
    @Test public void pointHasNoRealDuration() {
        TaskStore.Task task = new TaskStore.Task();
        task.start = task.due = 100L;
        assertEquals(100L, TaskTimes.rangeEnd(task));
        assertEquals(60100L, TaskTimes.drawingEnd(task));
    }
    @Test public void dateOnlyDoesNotShiftWithZone() {
        TaskStore.Task task = new TaskStore.Task();
        task.due = Instant.parse("2027-01-05T00:00:00Z").toEpochMilli();
        task.allDay = true;
        assertEquals(LocalDate.of(2027, 1, 5), TaskTimes.displayDay(task, ZoneId.of("America/New_York")));
    }
}
