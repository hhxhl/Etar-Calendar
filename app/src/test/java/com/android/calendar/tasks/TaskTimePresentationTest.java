package com.android.calendar.tasks;

import org.junit.Test;
import static org.junit.Assert.*;

public class TaskTimePresentationTest {
    @Test public void equalEndpointsArePointsEvenWhenDisabled() {
        TaskTimePresentation p = new TaskTimePresentation(100L, 100L, null, false, false);
        assertFalse(p.range);
        assertFalse(p.hideStart);
        assertFalse(p.hideDue);
        assertFalse(p.reminderPoint());
    }
    @Test public void allEqualWithAlarm() {
        TaskTimePresentation p = new TaskTimePresentation(100L, 100L, 100L, false, true);
        assertFalse(p.range);
        assertTrue(p.hideStart);
        assertTrue(p.hideDue);
        assertTrue(p.reminderPoint());
    }
    @Test public void realRangeIsPreserved() {
        TaskTimePresentation p = new TaskTimePresentation(100L, 200L, 100L, false, true);
        assertTrue(p.range);
        assertTrue(p.hideStart);
        assertFalse(p.hideDue);
    }
    @Test public void allDayAndTimedAlarmAreDistinct() {
        TaskTimePresentation p = new TaskTimePresentation(100L, null, 100L, true, true);
        assertFalse(p.hasDuplicates());
        assertFalse(p.hideStart);
    }
    @Test public void importedEqualEndpointsRemainEditable() {
        assertTrue(TaskTimePresentation.validRange(100L, 100L, false, true));
        assertFalse(TaskTimePresentation.validRange(100L, 100L, false, false));
        assertFalse(TaskTimePresentation.validRange(200L, 100L, true, true));
    }
}
