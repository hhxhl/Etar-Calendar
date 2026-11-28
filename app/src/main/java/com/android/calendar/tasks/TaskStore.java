package com.android.calendar.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.Instant;
import java.util.Objects;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Local source of truth. Column names follow the OpenTasks contract where applicable. */
public final class TaskStore extends SQLiteOpenHelper {
    private static TaskStore instance;
    public static synchronized TaskStore get(Context context) {
        if (instance == null) instance = new TaskStore(context.getApplicationContext());
        return instance;
    }
    private final Context appContext;
    private TaskStore(Context context) {
        super(context, "tasks.db", null, 1);
        appContext = context.getApplicationContext();
    }
    public static final String ACTION_TASKS_CHANGED = "com.android.calendar.tasks.CHANGED";

    private void notifyChanged() { notifyChanged(true); }

    void notifyChanged(boolean alarmsChanged) {
        if (alarmsChanged) TaskAlarmReceiver.schedule(appContext);
        com.android.calendar.Utils.notifyWidgetDataChanged(appContext);
        appContext.sendBroadcast(new android.content.Intent(ACTION_TASKS_CHANGED)
                .setPackage(appContext.getPackageName()));
    }
    private void requestTaskSync(long listId) {
        TaskList list = findList(listId);
        if (list != null && !list.isLocal() && list.syncEnabled)
            appContext.getContentResolver().notifyChange(TaskContract.uri(appContext, "tasks"), null,
                    android.content.ContentResolver.NOTIFY_SYNC_TO_NETWORK);
    }

    /** Rebuild derived local fields after an entire remote transaction, not between sub-row writes. */
    boolean reconcileSyncedTask(SQLiteDatabase db, long id, Task before) {
        Task after = find(id);
        if (after == null) return before != null;
        Long alarm = after.alarmTime();
        boolean changed = before == null || before.done != after.done
                || !Objects.equals(before.alarmTime(), alarm);
        ContentValues values = new ContentValues();
        values.put("alarm_at", alarm);
        if (changed) values.putNull("alarm_fired");
        if (before == null || !Objects.equals(before.displayTime(), after.displayTime())
                || before.allDay != after.allDay || !Objects.equals(before.rule, after.rule)) {
            values.put("recurrence_anchor", after.displayTime());
            values.putNull("repeat_minute");
        }
        db.update("tasks", values, "_id=?", new String[]{Long.toString(id)});
        return changed;
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        createTables(db);
        createIndexes(db);
        db.execSQL("INSERT INTO tasklists(_id,list_name) VALUES(1,'')");
    }

    private static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasklists (_id INTEGER PRIMARY KEY AUTOINCREMENT, account_name TEXT NOT NULL DEFAULT 'Local', account_type TEXT NOT NULL DEFAULT 'org.dmfs.account.LOCAL', list_name TEXT NOT NULL, list_color INTEGER NOT NULL DEFAULT 0, visible INTEGER NOT NULL DEFAULT 1, sync_enabled INTEGER NOT NULL DEFAULT 0, _sync_id TEXT, sync_version TEXT, sync1 TEXT, list_owner TEXT, list_access_level INTEGER NOT NULL DEFAULT 700)");
        db.execSQL("CREATE TABLE tasks (_id INTEGER PRIMARY KEY AUTOINCREMENT, list_id INTEGER NOT NULL REFERENCES tasklists(_id) ON DELETE CASCADE, _uid TEXT NOT NULL, _sync_id TEXT, sync_version INTEGER, sync1 TEXT, sync2 INTEGER NOT NULL DEFAULT 0, original_instance_id INTEGER, original_instance_sync_id TEXT, original_instance_time INTEGER, original_instance_allday INTEGER, _dirty INTEGER NOT NULL DEFAULT 1, _deleted INTEGER NOT NULL DEFAULT 0, title TEXT, description TEXT, location TEXT, geo TEXT, organizer TEXT, class INTEGER, duration TEXT, rdate TEXT, exdate TEXT, parent_id INTEGER, completed_is_allday INTEGER NOT NULL DEFAULT 0, due INTEGER, dtstart INTEGER, is_allday INTEGER NOT NULL DEFAULT 0, tz TEXT, status INTEGER NOT NULL DEFAULT 0, completed INTEGER, percent_complete INTEGER DEFAULT 0, priority INTEGER NOT NULL DEFAULT 0, rrule TEXT, created INTEGER, last_modified INTEGER, alarm_fired INTEGER, repeat_minute INTEGER, task_color INTEGER, url TEXT, show_countdown INTEGER NOT NULL DEFAULT 0, recurrence_anchor INTEGER, alarm_at INTEGER, UNIQUE(list_id,_uid))");
        db.execSQL("CREATE TABLE properties (property_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id INTEGER NOT NULL REFERENCES tasks(_id) ON DELETE CASCADE, mimetype TEXT NOT NULL, data0, data1, data2, data3, data4, data5, data6, data7, data8, data9, data10, data11, data12, data13, data14, data15)");
    }

    private static void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX task_due ON tasks(status, _deleted, due)");
        db.execSQL("CREATE INDEX task_list ON tasks(list_id)");
        db.execSQL("CREATE UNIQUE INDEX task_remote_id ON tasks(list_id,_sync_id) WHERE _sync_id IS NOT NULL");
        db.execSQL("CREATE INDEX task_alarm ON tasks(status, _deleted, alarm_at)");
        db.execSQL("CREATE INDEX property_task ON properties(task_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported task database version: " + oldVersion);
    }

    public static final class TaskList {
        public long id;
        public String name;
        public int color;
        public boolean visible, syncEnabled;
        public String accountName, accountType, remoteUrl;
        public int accessLevel;
        public boolean isLocal() { return "org.dmfs.account.LOCAL".equals(accountType); }
        public boolean isWritable() { return isLocal() || accessLevel > 200; }
        @Override public String toString() { return name; }
    }
    public static final class Task {
        public long id, listId = 1;
        public String title = "", notes = "", url = "", rule, tz;
        public static final int ALARM_ABSOLUTE=0, ALARM_START=1, ALARM_DUE=2;
        public Long due, start, anchor, alarmAbsolute;
        public boolean allDay, done, alarm, countdown, inProgress;
        public boolean writable = true, local = true;
        boolean hasRecurrenceDates, hasRecurrenceExceptions;
        String revision;
        public int alarmMode=ALARM_DUE, alarmOffset;
        public ZoneId zone() {
            try { return tz == null ? ZoneId.systemDefault() : ZoneId.of(tz); }
            catch (java.time.DateTimeException ex) { return ZoneId.systemDefault(); }
        }
        public Long alarmTime() { return TaskTimes.alarm(this); }
        public Long displayTime() { return TaskTimes.display(this); }
        public boolean displayAllDay() { return TaskTimes.dateOnly(this); }
        public boolean hasRange() { return TaskTimes.isRange(start, due); }
        public boolean rangeAllDay() { return hasRange() && allDay; }
        public long rangeStart(ZoneId calendarZone) { return TaskTimes.rangeStart(this); }
        public long rangeEnd(ZoneId calendarZone) { return TaskTimes.drawingEnd(this); }
        public int priority, color, listColor;
        public Integer repeatMinute;
    }
    public List<TaskList> lists() {
        return queryLists(null, null);
    }
    public List<TaskList> visibleLists() {
        return queryLists("visible=1", null);
    }
    public TaskList findList(long id) {
        List<TaskList> result = queryLists("_id=?", new String[]{Long.toString(id)});
        return result.isEmpty() ? null : result.get(0);
    }
    private List<TaskList> queryLists(String selection, String[] args) {
        ArrayList<TaskList> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("tasklists",
                new String[]{"_id","list_name","list_color","visible","account_name","account_type","sync_enabled","list_access_level","sync1"}, selection,args,null,null,"_id")) {
            while (c.moveToNext()) {
                TaskList l = new TaskList();
                l.id = c.getLong(0); l.name = c.getString(1); l.color = c.getInt(2);
                l.visible = c.getInt(3) != 0;
                l.accountName=c.getString(4); l.accountType=c.getString(5); l.syncEnabled=c.getInt(6)!=0;
                l.accessLevel=c.getInt(7); l.remoteUrl=c.getString(8);
                result.add(l);
            }
        }
        return result;
    }
    public void setListVisible(long id, boolean visible) {
        ContentValues v = new ContentValues();
        v.put("visible", visible ? 1 : 0);
        if (getWritableDatabase().update("tasklists", v, "_id=?", new String[]{Long.toString(id)}) > 0)
            notifyChanged(false);
    }
    public void setListColor(long id, int color) {
        ContentValues v = new ContentValues();
        v.put("list_color", color);
        if (getWritableDatabase().update("tasklists", v, "_id=?", new String[]{Long.toString(id)}) > 0)
            notifyChanged(false);
    }
    public long addList(String name) {
        ContentValues v = new ContentValues(); v.put("list_name",name.trim());
        long id = getWritableDatabase().insertOrThrow("tasklists",null,v);
        notifyChanged(false);
        return id;
    }
    public void renameList(long id,String name) {
        TaskList list = findList(id);
        if (list == null || !list.isLocal()) return;
        ContentValues v = new ContentValues(); v.put("list_name",name.trim());
        getWritableDatabase().update("tasklists",v,"_id=?",new String[]{Long.toString(id)});
        notifyChanged(false);
    }
    public void deleteList(long id) {
        TaskList list = findList(id);
        if (id == 1 || list == null || !list.isLocal()) return;
        List<Task> removed = tasks(id);
        if (getWritableDatabase().delete("tasklists","_id=?",new String[]{Long.toString(id)}) > 0) {
            for (Task task : removed) TaskAlarmReceiver.dismiss(appContext, task.id);
            notifyChanged();
        }
    }
    static final String ALARM_MIME = "vnd.android.cursor.item/alarm";
    // data0 = minutes before, data1 = DUE (1) or DTSTART (2), data2 = message,
    // data3 = action, data4 = absolute UTC trigger (Etar extension).
    private static final String SELECT = "SELECT t._id,t.list_id,COALESCE(t.title,''),COALESCE(t.description,''),t.due,t.recurrence_anchor,t.is_allday,t.tz,t.status,t.priority,t.rrule,a.property_id,t.repeat_minute,t.task_color,COALESCE(t.url,''),t.show_countdown,l.list_color,t.dtstart,a.data0,a.data1,a.data4,l.account_type,l.list_access_level,t.rdate,t.exdate,0,t.last_modified,t.sync1,t._dirty,t.status,t.completed,t.percent_complete FROM tasks t JOIN tasklists l ON l._id=t.list_id LEFT JOIN properties a ON a.property_id=(SELECT MIN(p.property_id) FROM properties p WHERE p.task_id=t._id AND p.mimetype='vnd.android.cursor.item/alarm') WHERE t._deleted=0 AND t.original_instance_id IS NULL";
    private static Task read(Cursor c) {
        Task t = new Task(); t.id=c.getLong(0); t.listId=c.getLong(1); t.title=c.getString(2); t.notes=c.getString(3);
        t.due=c.isNull(4)?null:c.getLong(4); t.anchor=c.isNull(5)?null:c.getLong(5); t.allDay=c.getInt(6)!=0;
        t.tz=c.getString(7); t.done=c.getInt(8)>=2; t.inProgress=c.getInt(8)==1; t.priority=c.getInt(9); t.rule=c.getString(10); t.alarm=!c.isNull(11);
        t.repeatMinute=c.isNull(12)?null:c.getInt(12);
        t.color=c.getInt(13); t.url=c.getString(14); t.countdown=c.getInt(15)!=0;
        t.listColor=c.getInt(16); t.start=c.isNull(17)?null:c.getLong(17);
        t.alarmOffset=c.getInt(18);
        t.alarmAbsolute=c.isNull(20)?null:c.getLong(20);
        t.alarmMode=t.alarmAbsolute!=null?Task.ALARM_ABSOLUTE:c.getInt(19)==2?Task.ALARM_START:Task.ALARM_DUE;
        t.local=TaskContract.LOCAL_ACCOUNT.equals(c.getString(21));
        t.writable=t.local || c.getInt(22)>200;
        t.hasRecurrenceDates = !android.text.TextUtils.isEmpty(c.getString(23)) || !android.text.TextUtils.isEmpty(c.getString(24));
        // Include visible fields and server ETag; do not include derived alarm_fired.
        t.revision = revision(c);
        return t;
    }
    private static String revision(Cursor cursor) {
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            // Exclude list color/access and dirty state: they may change independently.
            if (i == 16 || i == 22 || i == 28) continue;
            String value = cursor.isNull(i) ? null : cursor.getString(i);
            if (value == null) token.append("-;");
            else token.append(value.length()).append(':').append(value).append(';');
        }
        return token.toString();
    }

    private static boolean sameAlarm(Task a, Task b) {
        return a.alarm == b.alarm && (!a.alarm ||
                (a.alarmMode == b.alarmMode && a.alarmOffset == b.alarmOffset
                        && Objects.equals(a.alarmAbsolute, b.alarmAbsolute)));
    }

    private static boolean sameSyncedFields(Task a, Task b) {
        return a.listId == b.listId && Objects.equals(a.title, b.title.trim())
                && Objects.equals(a.notes, b.notes) && Objects.equals(a.url, b.url)
                && Objects.equals(a.start, b.start) && Objects.equals(a.due, b.due)
                && a.allDay == b.allDay && Objects.equals(a.tz, b.tz)
                && a.priority == b.priority && Objects.equals(a.rule, b.rule)
                && sameAlarm(a, b);
    }

    private static void writeAlarm(SQLiteDatabase db, Task t) {
        String[] alarmArgs = {Long.toString(t.id), ALARM_MIME};
        if (!t.alarm) {
            // An explicit disable removes reminders; ordinary metadata edits never touch them.
            db.delete("properties", "task_id=? AND mimetype=?", alarmArgs);
            return;
        }
        Long first = null;
        try (Cursor cursor = db.query("properties", new String[]{"property_id"},
                "task_id=? AND mimetype=?", alarmArgs, null, null, "property_id", "1")) {
            if (cursor.moveToFirst()) first = cursor.getLong(0);
        }
        ContentValues v = new ContentValues();
        v.put("task_id", t.id); v.put("mimetype", ALARM_MIME);
        v.putNull("data0"); v.putNull("data1"); v.putNull("data4");
        if (t.alarmMode == Task.ALARM_ABSOLUTE) v.put("data4", t.alarmAbsolute);
        else {
            v.put("data0", t.alarmOffset);
            v.put("data1", t.alarmMode == Task.ALARM_START ? 2 : 1);
        }
        if (first == null) {
            v.put("data3", 1);
            db.insertOrThrow("properties", null, v);
        } else {
            db.update("properties", v, "property_id=?", new String[]{Long.toString(first)});
        }
    }
    public List<Task> tasks(long listId) {
        return queryTasks(listId, false, false);
    }
    public List<Task> visibleTasks(long listId) {
        return queryTasks(listId, true, false);
    }
    public List<Task> visibleTasks(long listId, android.os.CancellationSignal signal) {
        return queryTasks(listId, true, false, signal);
    }
    private static final String DISPLAY_TIME = "COALESCE(t.due,t.dtstart,t.alarm_at)";
    private static final String DISPLAY_ALL_DAY = "CASE WHEN t.due IS NOT NULL THEN t.is_allday WHEN t.dtstart IS NOT NULL THEN t.is_allday ELSE 0 END";
    public List<Task> visibleUndatedTasks(long listId) {
        return queryTasks(listId, true, true);
    }
    public List<Task> visibleUndatedTasks(long listId, android.os.CancellationSignal signal) {
        return queryTasks(listId, true, true, signal);
    }
    private List<Task> queryTasks(long listId, boolean visibleOnly, boolean undatedOnly) {
        return queryTasks(listId, visibleOnly, undatedOnly, null);
    }
    private List<Task> queryTasks(long listId, boolean visibleOnly, boolean undatedOnly, android.os.CancellationSignal signal) {
        ArrayList<Task> result=new ArrayList<>();
        String filter=listId==0?"":" AND t.list_id=?";
        if (visibleOnly) filter += " AND l.visible=1";
        if (undatedOnly) filter += " AND "+DISPLAY_TIME+" IS NULL";
        try(Cursor c=getReadableDatabase().rawQuery(SELECT+filter+" ORDER BY t.status>=2,"+DISPLAY_TIME+" IS NULL,"+DISPLAY_TIME+",t._id",listId==0?null:new String[]{Long.toString(listId)}, signal)) {
            while(c.moveToNext()) {
                if (signal != null) signal.throwIfCanceled();
                result.add(read(c));
            }
        }
        return result;
    }
    public List<Task> datedTasks(long from,long until,long utcFrom,long utcUntil) {
        return datedTasks(from, until, utcFrom, utcUntil, null);
    }
    public List<Task> datedTasks(long from,long until,long utcFrom,long utcUntil, android.os.CancellationSignal signal) {
        ArrayList<Task> result=new ArrayList<>();
        // Query overlapping ranges as well as isolated display points.
        String where=" AND ((t.dtstart IS NOT NULL AND t.due IS NOT NULL"
                + " AND ((t.is_allday=0 AND t.dtstart<?) OR (t.is_allday=1 AND t.dtstart<?))"
                + " AND ((t.is_allday=0 AND t.due>=?) OR (t.is_allday=1 AND t.due>=?)))"
                + " OR ((t.dtstart IS NULL OR t.due IS NULL)"
                + " AND (("+DISPLAY_ALL_DAY+"=0 AND "+DISPLAY_TIME+">=? AND "+DISPLAY_TIME+"<?)"
                + " OR ("+DISPLAY_ALL_DAY+"=1 AND "+DISPLAY_TIME+">=? AND "+DISPLAY_TIME+"<?))))"
                + " AND l.visible=1";
        try(Cursor c=getReadableDatabase().rawQuery(SELECT+where+" ORDER BY "+DISPLAY_TIME+",t._id",
                new String[]{Long.toString(until),Long.toString(utcUntil),Long.toString(from),Long.toString(utcFrom),
                        Long.toString(from),Long.toString(until),Long.toString(utcFrom),Long.toString(utcUntil)}, signal)){
            while(c.moveToNext()) {
                if (signal != null) signal.throwIfCanceled();
                result.add(read(c));
            }
        }
        return result;
    }
    public List<Task> overdueTasks(long from, long utcFrom) {
        ArrayList<Task> result = new ArrayList<>();
        String where = " AND t.status IN (0,1) AND t.due IS NOT NULL"
                + " AND ((t.is_allday=0 AND t.due<?) OR (t.is_allday=1 AND t.due<?))"
                + " AND l.visible=1";
        try (Cursor c = getReadableDatabase().rawQuery(SELECT + where + " ORDER BY t.due,t._id",
                new String[]{Long.toString(from), Long.toString(utcFrom)})) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }

    public Task find(long id) {
        try(Cursor c=getReadableDatabase().rawQuery(SELECT+" AND t._id=?",new String[]{Long.toString(id)})) {return c.moveToFirst()?read(c):null;}
    }
    public long save(Task t) {
        return save(t, t.done);
    }

    /** Persist edited fields and completion (including advancing recurrence) atomically. */
    public long save(Task t, boolean completed) {
        SQLiteDatabase db = getWritableDatabase();
        boolean resetNotification;
        boolean syncChanged;
        db.beginTransaction();
        try {
            Task old = t.id == 0 ? null : find(t.id);
            TaskList target = findList(t.listId);
            if (target == null || !target.isWritable() || (t.id != 0 && (old == null || !old.writable))) return 0;
            if (old != null && t.revision != null && !Objects.equals(t.revision, old.revision)) return -1;
            // Moving a synced resource requires a remote delete plus a new upload, not an ID change.
            if (old != null && old.listId != t.listId && !findList(old.listId).isLocal()) return 0;
            t.local = target.isLocal();
            t.alarm = t.alarm && t.alarmTime() != null;
            boolean completionChanged = completed != (old != null && old.done);
            syncChanged = old == null || completionChanged || !sameSyncedFields(old, t);
            if (!syncChanged && old.color == t.color && old.countdown == t.countdown) return t.id;
            resetNotification = old == null || !Objects.equals(old.due, t.due)
                    || old.alarm != t.alarm || !Objects.equals(old.alarmTime(), t.alarmTime())
                    || !Objects.equals(old.start, t.start) || old.allDay != t.allDay || completionChanged;
            ContentValues v = new ContentValues();
            v.put("list_id", t.listId);
            v.put("title", t.title.trim());
            v.put("description", t.notes);
            if (t.color == 0) v.putNull("task_color"); else v.put("task_color", t.color);
            v.put("url", t.url == null ? "" : t.url);
            v.put("show_countdown", t.due != null && t.countdown ? 1 : 0);
            v.put("due", t.due);
            v.put("dtstart", t.start);
            if (t.due != null || t.start == null) v.putNull("duration");
            v.put("recurrence_anchor", t.anchor);
            v.put("alarm_at", t.alarmTime());
            v.put("repeat_minute", t.repeatMinute);
            v.put("is_allday", t.allDay ? 1 : 0);
            v.put("tz", t.tz);
            v.put("priority", t.priority);
            v.put("rrule", t.rule);
            if (syncChanged) {
                v.put("last_modified", System.currentTimeMillis());
                v.put("_dirty", target.isLocal() ? 0 : 1);
            } else {
                // Pure presentation edits must never rewrite standard or derived fields.
                v.clear();
                if (t.color == 0) v.putNull("task_color"); else v.put("task_color", t.color);
                v.put("show_countdown", t.due != null && t.countdown ? 1 : 0);
            }
            if (resetNotification) v.putNull("alarm_fired");
            if (completionChanged) applyCompletion(db, v, t, completed);
            if (t.id == 0) {
                v.put("_uid", UUID.randomUUID().toString());
                v.put("created", System.currentTimeMillis());
                t.id = db.insertOrThrow("tasks", null, v);
            } else {
                db.update("tasks", v, "_id=?", new String[]{Long.toString(t.id)});
            }
            if (syncChanged && (old == null || !sameAlarm(old, t))) writeAlarm(db, t);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (resetNotification) TaskAlarmReceiver.dismiss(appContext, t.id);
        // Title, notes, URL and color changes do not change the next alarm.
        notifyChanged(resetNotification);
        if (syncChanged) requestTaskSync(t.listId);
        return t.id;
    }

    public void delete(long id) {
        Task task = find(id);
        if (task == null || !task.writable) return;
        TaskList list = findList(task.listId);
        if (list == null) return;
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {Long.toString(id)};
        int changed;
        if (list.isLocal()) changed = db.delete("tasks", "_id=?", args);
        else {
            ContentValues values = new ContentValues(); values.put("_deleted", 1); values.put("_dirty", 1);
            values.putNull("alarm_at");
            changed = db.update("tasks", values, "_id=?", args);
        }
        if (changed > 0) {
            TaskAlarmReceiver.dismiss(appContext, id);
            notifyChanged();
            requestTaskSync(task.listId);
        }
    }

    static Long nextDue(Task t) { return TaskRecurrence.nextLocal(t); }

    private static void applyCompletion(SQLiteDatabase db, ContentValues v, Task t, boolean complete) {
        if (complete && !t.local && t.id != 0 && t.rule != null) {
            try (Cursor cursor = db.rawQuery("SELECT 1 FROM tasks x JOIN tasks master ON master._id=?"
                    + " WHERE x._deleted=0 AND (x.original_instance_id=master._id"
                    + " OR (x.list_id=master.list_id AND x.original_instance_sync_id=master._sync_id)) LIMIT 1",
                    new String[]{Long.toString(t.id)})) {
                t.hasRecurrenceExceptions = cursor.moveToFirst();
            }
        }
        TaskRecurrence.Advance next = complete ? TaskRecurrence.next(t) : null;
        if (next != null) {
            boolean dateOnly = t.displayAllDay();
            ZoneId zone = t.zone();
            t.due = TaskRecurrence.shift(t.due, t.allDay, zone, next, dateOnly);
            t.start = TaskRecurrence.shift(t.start, t.allDay, zone, next, dateOnly);
            t.alarmAbsolute = TaskRecurrence.shift(t.alarmAbsolute, false, zone, next, dateOnly);
            t.rule = next.rule;
            v.put("rrule", t.rule);
            v.put("due", t.due);
            v.put("dtstart", t.start);
            v.put("alarm_at", t.alarmTime());
            v.put("status", 0);
            v.put("percent_complete", 0);
            v.putNull("completed");
            t.done = false;
        } else {
            v.put("status", complete ? 2 : 0);
            v.put("percent_complete", complete ? 100 : 0);
            if (complete) v.put("completed", System.currentTimeMillis());
            else v.putNull("completed");
            t.done = complete;
        }
        t.inProgress = false;
        v.putNull("alarm_fired");
    }

    public void complete(long id, boolean complete) {
        completeInternal(id, complete, false, false, null);
    }

    /** A stale notification or list row must not advance a newer occurrence. */
    public void complete(long id, boolean complete, Long expectedDue) {
        completeInternal(id, complete, true, false, expectedDue);
    }

    public void completeAlarm(long id, long expectedAlarm) {
        completeInternal(id, true, true, true, expectedAlarm);
    }

    private void completeInternal(long id, boolean complete, boolean checkDue, boolean checkAlarm, Long expectedDue) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Task t = find(id);
            if (t == null || !t.writable || t.done == complete || (checkDue && !Objects.equals(checkAlarm ? t.alarmTime() : t.displayTime(), expectedDue))) return;
            ContentValues v = new ContentValues();
            Long previousAbsolute = t.alarmAbsolute;
            applyCompletion(db, v, t, complete);
            v.put("last_modified", System.currentTimeMillis());
            TaskList list = findList(t.listId);
            v.put("_dirty", list != null && !list.isLocal() ? 1 : 0);
            if (!Objects.equals(previousAbsolute, t.alarmAbsolute)) writeAlarm(db, t);
            db.update("tasks", v, "_id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        TaskAlarmReceiver.dismiss(appContext, id);
        notifyChanged();
        Task task = find(id);
        if (task != null) requestTaskSync(task.listId);
    }

    private static final String ALARM_FILTER = " AND t.status IN (0,1)"
            + " AND t.alarm_at IS NOT NULL AND t.alarm_fired IS NULL"
            + " AND EXISTS(SELECT 1 FROM properties p WHERE p.task_id=t._id AND p.mimetype='vnd.android.cursor.item/alarm')";
    private static final String ALARMS = " FROM tasks t WHERE t._deleted=0 AND t.original_instance_id IS NULL" + ALARM_FILTER;
    public Long nextAlarm() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT MIN(t.alarm_at)"+ALARMS,null)){return c.moveToFirst()&&!c.isNull(0)?c.getLong(0):null;}
    }
    public List<Task> dueAlarms(long now) {
        ArrayList<Task> result=new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(SELECT + ALARM_FILTER
                + " AND t.alarm_at<=? ORDER BY t.alarm_at,t._id", new String[]{Long.toString(now)})) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }
    /** Recompute cached relative alarms after a change in system time zone.
     * No standard task field, dirty flag or recurrence rule is changed.
     */
    void refreshRelativeDateAlarms() {
        SQLiteDatabase db = getWritableDatabase();
        java.util.List<Long> changed = new ArrayList<>();
        db.beginTransaction();
        try {
            try (Cursor cursor = db.rawQuery(SELECT + " AND t.is_allday=1 AND t.status IN (0,1)"
                    + " AND a.property_id IS NOT NULL AND a.data4 IS NULL", null)) {
                while (cursor.moveToNext()) {
                    Task task = read(cursor);
                    Long expected = task.alarmTime();
                    ContentValues values = new ContentValues();
                    values.put("alarm_at", expected);
                    values.putNull("alarm_fired");
                    String condition = expected == null ? "_id=? AND alarm_at IS NOT NULL"
                            : "_id=? AND (alarm_at IS NULL OR alarm_at!=?)";
                    String[] args = expected == null ? new String[]{Long.toString(task.id)}
                            : new String[]{Long.toString(task.id), Long.toString(expected)};
                    if (db.update("tasks", values, condition, args) > 0) changed.add(task.id);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        for (long id : changed) TaskAlarmReceiver.dismiss(appContext, id);
        if (!changed.isEmpty()) notifyChanged(false);
    }

    /** Verify the alarm immediately before publishing, under the same write transaction. */
    void dispatchAlarm(long id, long expected, java.util.function.Consumer<Task> publish) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Task task = null;
            try (Cursor cursor = db.rawQuery(SELECT + ALARM_FILTER
                    + " AND t._id=? AND t.alarm_at=?", new String[]{Long.toString(id), Long.toString(expected)})) {
                if (cursor.moveToFirst()) task = read(cursor);
            }
            if (task != null && Objects.equals(task.alarmTime(), expected)) {
                publish.accept(task);
                markFired(id, expected);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void markFired(long id,long due) {
        ContentValues v=new ContentValues();v.put("alarm_fired",due);
        getWritableDatabase().update("tasks",v,"_id=? AND alarm_at=? AND status IN (0,1)",new String[]{Long.toString(id),Long.toString(due)});
    }
}
