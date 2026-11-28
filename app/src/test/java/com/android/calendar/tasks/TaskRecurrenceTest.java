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
}
