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
    @Test public void yearlyRetainsLeapDay(){
        assertEquals(Long.valueOf(Instant.parse("2028-02-29T00:00:00Z").toEpochMilli()),
            TaskStore.nextDue(task("2027-02-28T00:00:00Z","2024-02-29T00:00:00Z","FREQ=YEARLY")));
    }
}
