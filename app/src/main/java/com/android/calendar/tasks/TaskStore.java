package com.android.calendar.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
        super(context, "tasks.db", null, 3);
        appContext = context.getApplicationContext();
    }
    public static final String ACTION_TASKS_CHANGED = "com.android.calendar.tasks.CHANGED";

    private void notifyChanged() { notifyChanged(true); }

    private void notifyChanged(boolean alarmsChanged) {
        if (alarmsChanged) TaskAlarmReceiver.schedule(appContext);
        com.android.calendar.Utils.notifyWidgetDataChanged(appContext);
        appContext.sendBroadcast(new android.content.Intent(ACTION_TASKS_CHANGED)
                .setPackage(appContext.getPackageName()));
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        createTables(db);
        createIndexes(db);
        db.execSQL("INSERT INTO tasklists(_id,list_name) VALUES(1,'')");
    }

    private static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasklists (_id INTEGER PRIMARY KEY AUTOINCREMENT, account_name TEXT NOT NULL DEFAULT 'Local', account_type TEXT NOT NULL DEFAULT 'org.dmfs.account.LOCAL', list_name TEXT NOT NULL, list_color INTEGER NOT NULL DEFAULT 0, visible INTEGER NOT NULL DEFAULT 1, sync_enabled INTEGER NOT NULL DEFAULT 0, _sync_id TEXT, sync_version TEXT)");
        db.execSQL("CREATE TABLE tasks (_id INTEGER PRIMARY KEY AUTOINCREMENT, list_id INTEGER NOT NULL REFERENCES tasklists(_id) ON DELETE CASCADE, _uid TEXT NOT NULL UNIQUE, _sync_id TEXT, sync_version TEXT, _dirty INTEGER NOT NULL DEFAULT 1, _deleted INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', due INTEGER, dtstart INTEGER, is_allday INTEGER NOT NULL DEFAULT 0, tz TEXT, status INTEGER NOT NULL DEFAULT 0, completed INTEGER, percent_complete INTEGER NOT NULL DEFAULT 0, priority INTEGER NOT NULL DEFAULT 0, rrule TEXT, created INTEGER NOT NULL, last_modified INTEGER NOT NULL, alarm_fired INTEGER, repeat_minute INTEGER, item_color INTEGER NOT NULL DEFAULT 0, url TEXT NOT NULL DEFAULT '', show_countdown INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE properties (property_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id INTEGER NOT NULL REFERENCES tasks(_id) ON DELETE CASCADE, mimetype TEXT NOT NULL, data0 INTEGER, data1 INTEGER, data2 TEXT, data3 INTEGER)");
    }

    private static void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX task_due ON tasks(status, _deleted, due)");
        db.execSQL("CREATE INDEX task_list ON tasks(list_id)");
        db.execSQL("CREATE INDEX property_task ON properties(task_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 2 && newVersion == 3) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN item_color INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE tasks ADD COLUMN url TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE tasks ADD COLUMN show_countdown INTEGER NOT NULL DEFAULT 0");
            return;
        }
        if (oldVersion != 1 || newVersion != 3) {
            throw new IllegalStateException("Missing task database migration");
        }
        // The v1 rebuild below creates the current schema; new fields use defaults.
        // SQLiteOpenHelper already wraps upgrades in a transaction. Rename parents
        // before children so the old foreign keys continue referencing old tables.
        db.execSQL("ALTER TABLE tasklists RENAME TO old_tasklists");
        db.execSQL("ALTER TABLE tasks RENAME TO old_tasks");
        db.execSQL("ALTER TABLE properties RENAME TO old_properties");
        createTables(db);
        db.execSQL("INSERT INTO tasklists SELECT * FROM old_tasklists");
        db.execSQL("INSERT INTO tasks (_id,list_id,_uid,_sync_id,sync_version,_dirty,_deleted,title,description,due,dtstart,is_allday,tz,status,completed,percent_complete,priority,rrule,created,last_modified,alarm_fired) "
                + "SELECT _id,list_id,_uid,_sync_id,sync_version,_dirty,_deleted,title,description,due,dtstart,is_allday,tz,status,completed,percent_complete,priority,rrule,created,last_modified,alarm_fired FROM old_tasks");
        db.execSQL("INSERT INTO properties SELECT * FROM old_properties");
        db.execSQL("DROP TABLE old_properties");
        db.execSQL("DROP TABLE old_tasks");
        db.execSQL("DROP TABLE old_tasklists");
        createIndexes(db);
    }
    public static final class TaskList {
        public long id;
        public String name;
        public int color;
        public boolean visible;
        @Override public String toString() { return name; }
    }
    public static final class Task {
        public long id, listId = 1;
        public String title = "", notes = "", url = "", rule, tz;
        public Long due, anchor;
        public boolean allDay, done, alarm, countdown;
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
                new String[]{"_id","list_name","list_color","visible"}, selection,args,null,null,"_id")) {
            while (c.moveToNext()) {
                TaskList l = new TaskList();
                l.id = c.getLong(0); l.name = c.getString(1); l.color = c.getInt(2);
                l.visible = c.getInt(3) != 0;
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
        ContentValues v = new ContentValues(); v.put("list_name",name.trim());
        getWritableDatabase().update("tasklists",v,"_id=?",new String[]{Long.toString(id)});
        notifyChanged(false);
    }
    public void deleteList(long id) {
        if (id == 1) return;
        List<Task> removed = tasks(id);
        if (getWritableDatabase().delete("tasklists","_id=?",new String[]{Long.toString(id)}) > 0) {
            for (Task task : removed) TaskAlarmReceiver.dismiss(appContext, task.id);
            notifyChanged();
        }
    }
    private static final String SELECT = "SELECT t._id,t.list_id,t.title,t.description,t.due,t.dtstart,t.is_allday,t.tz,t.status,t.priority,t.rrule, EXISTS(SELECT 1 FROM properties p WHERE p.task_id=t._id AND p.mimetype='vnd.android.cursor.item/alarm'),t.repeat_minute,t.item_color,t.url,t.show_countdown, (SELECT l.list_color FROM tasklists l WHERE l._id=t.list_id) FROM tasks t WHERE t._deleted=0";
    private static Task read(Cursor c) {
        Task t = new Task(); t.id=c.getLong(0); t.listId=c.getLong(1); t.title=c.getString(2); t.notes=c.getString(3);
        t.due=c.isNull(4)?null:c.getLong(4); t.anchor=c.isNull(5)?null:c.getLong(5); t.allDay=c.getInt(6)!=0;
        t.tz=c.getString(7); t.done=c.getInt(8)==2; t.priority=c.getInt(9); t.rule=c.getString(10); t.alarm=c.getInt(11)!=0;
        t.repeatMinute=c.isNull(12)?null:c.getInt(12);
        t.color=c.getInt(13); t.url=c.getString(14); t.countdown=c.getInt(15)!=0;
        t.listColor=c.getInt(16);
        return t;
    }
    public List<Task> tasks(long listId) {
        return queryTasks(listId, false, null);
    }
    public List<Task> visibleTasks(long listId) {
        return queryTasks(listId, true, null);
    }
    public List<Task> visibleReminders(long listId) {
        return queryTasks(listId, true, true);
    }
    public List<Task> visibleUndatedTasks(long listId) {
        return queryTasks(listId, true, false);
    }
    private List<Task> queryTasks(long listId, boolean visibleOnly, Boolean dated) {
        ArrayList<Task> result=new ArrayList<>();
        String filter=listId==0?"":" AND t.list_id=?";
        if (visibleOnly) filter += " AND EXISTS(SELECT 1 FROM tasklists l WHERE l._id=t.list_id AND l.visible=1)";
        if (dated != null) filter += dated ? " AND t.due IS NOT NULL" : " AND t.due IS NULL";
        try(Cursor c=getReadableDatabase().rawQuery(SELECT+filter+" ORDER BY t.status=2,t.due IS NULL,t.due,t._id",listId==0?null:new String[]{Long.toString(listId)})) {
            while(c.moveToNext()) result.add(read(c));
        }
        return result;
    }
    public List<Task> datedTasks(long from,long until,long utcFrom,long utcUntil) {
        ArrayList<Task> result=new ArrayList<>();
        String where=" AND ((t.is_allday=0 AND t.due>=? AND t.due<?) OR (t.is_allday=1 AND t.due>=? AND t.due<?)) AND EXISTS(SELECT 1 FROM tasklists l WHERE l._id=t.list_id AND l.visible=1)";
        try(Cursor c=getReadableDatabase().rawQuery(SELECT+where+" ORDER BY t.due,t._id",
                new String[]{Long.toString(from),Long.toString(until),Long.toString(utcFrom),Long.toString(utcUntil)})){
            while(c.moveToNext())result.add(read(c));
        }
        return result;
    }
    public List<Task> overdueTasks(long from, long utcFrom) {
        ArrayList<Task> result = new ArrayList<>();
        String where = " AND t.status=0 AND t.due IS NOT NULL"
                + " AND ((t.is_allday=0 AND t.due<?) OR (t.is_allday=1 AND t.due<?))"
                + " AND EXISTS(SELECT 1 FROM tasklists l WHERE l._id=t.list_id AND l.visible=1)";
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
        db.beginTransaction();
        try {
            Task old = t.id == 0 ? null : find(t.id);
            if (t.id != 0 && old == null) return 0;
            t.alarm = t.alarm && t.due != null && !t.allDay;
            boolean completionChanged = completed != (old != null && old.done);
            resetNotification = old == null || !Objects.equals(old.due, t.due)
                    || old.alarm != t.alarm || old.allDay != t.allDay || completionChanged;
            ContentValues v = new ContentValues();
            v.put("list_id", t.listId);
            v.put("title", t.title.trim());
            v.put("description", t.notes);
            v.put("item_color", t.color);
            v.put("url", t.url == null ? "" : t.url);
            v.put("show_countdown", t.due != null && t.countdown ? 1 : 0);
            v.put("due", t.due);
            v.put("dtstart", t.anchor);
            v.put("repeat_minute", t.repeatMinute);
            v.put("is_allday", t.allDay ? 1 : 0);
            v.put("tz", t.tz);
            v.put("priority", t.priority);
            v.put("rrule", t.rule);
            v.put("last_modified", System.currentTimeMillis());
            v.put("_dirty", 1);
            if (resetNotification) v.putNull("alarm_fired");
            if (completionChanged) applyCompletion(v, t, completed);
            if (t.id == 0) {
                v.put("_uid", UUID.randomUUID().toString());
                v.put("created", System.currentTimeMillis());
                t.id = db.insertOrThrow("tasks", null, v);
            } else {
                db.update("tasks", v, "_id=?", new String[]{Long.toString(t.id)});
            }
            if (old == null || old.alarm != t.alarm) {
                db.delete("properties", "task_id=? AND mimetype=?",
                        new String[]{Long.toString(t.id), "vnd.android.cursor.item/alarm"});
                if (t.alarm) {
                    ContentValues property = new ContentValues();
                    property.put("task_id", t.id);
                    property.put("mimetype", "vnd.android.cursor.item/alarm");
                    property.put("data0", 0);
                    property.put("data1", 1);
                    property.put("data3", 1);
                    db.insertOrThrow("properties", null, property);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (resetNotification) TaskAlarmReceiver.dismiss(appContext, t.id);
        // Title, notes, URL and color changes do not change the next alarm.
        notifyChanged(resetNotification);
        return t.id;
    }

    public void delete(long id) {
        if (getWritableDatabase().delete("tasks", "_id=?", new String[]{Long.toString(id)}) > 0) {
            TaskAlarmReceiver.dismiss(appContext, id);
            notifyChanged();
        }
    }

    /** Preset recurrence: preserve original day-of-month and wall-clock time. */
    static Long nextDue(Task t) {
        if(t.due==null || t.rule==null) return null;
        ZoneId zone=t.allDay?ZoneOffset.UTC:ZoneId.of(t.tz==null?ZoneId.systemDefault().getId():t.tz);
        ZonedDateTime current=Instant.ofEpochMilli(t.due).atZone(zone);
        ZonedDateTime anchor=Instant.ofEpochMilli(t.anchor==null?t.due:t.anchor).atZone(zone);
        LocalTime wallTime = t.allDay ? LocalTime.MIDNIGHT
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
    private static void applyCompletion(ContentValues v, Task t, boolean complete) {
        Long next = complete ? nextDue(t) : null;
        if (next != null) {
            v.put("due", next);
            v.put("status", 0);
            v.put("percent_complete", 0);
            v.putNull("completed");
            t.due = next;
            t.done = false;
        } else {
            v.put("status", complete ? 2 : 0);
            v.put("percent_complete", complete ? 100 : 0);
            if (complete) v.put("completed", System.currentTimeMillis());
            else v.putNull("completed");
            t.done = complete;
        }
        v.putNull("alarm_fired");
    }

    public void complete(long id, boolean complete) {
        completeInternal(id, complete, false, null);
    }

    /** A stale notification or list row must not advance a newer occurrence. */
    public void complete(long id, boolean complete, Long expectedDue) {
        completeInternal(id, complete, true, expectedDue);
    }

    private void completeInternal(long id, boolean complete, boolean checkDue, Long expectedDue) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Task t = find(id);
            if (t == null || t.done == complete || (checkDue && !Objects.equals(t.due, expectedDue))) return;
            ContentValues v = new ContentValues();
            applyCompletion(v, t, complete);
            v.put("last_modified", System.currentTimeMillis());
            v.put("_dirty", 1);
            db.update("tasks", v, "_id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        TaskAlarmReceiver.dismiss(appContext, id);
        notifyChanged();
    }

    private static final String ALARM_FILTER = " AND t.status=0 AND t.is_allday=0"
            + " AND t.due IS NOT NULL AND t.alarm_fired IS NULL"
            + " AND EXISTS(SELECT 1 FROM properties p WHERE p.task_id=t._id AND p.mimetype='vnd.android.cursor.item/alarm')";
    private static final String ALARMS = " FROM tasks t WHERE t._deleted=0" + ALARM_FILTER;
    public Long nextAlarm() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT MIN(t.due)"+ALARMS,null)){return c.moveToFirst()&&!c.isNull(0)?c.getLong(0):null;}
    }
    public List<Task> dueAlarms(long now) {
        ArrayList<Task> result=new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(SELECT + ALARM_FILTER
                + " AND t.due<=? ORDER BY t.due,t._id", new String[]{Long.toString(now)})) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }
    public void markFired(long id,long due) {
        ContentValues v=new ContentValues();v.put("alarm_fired",due);
        getWritableDatabase().update("tasks",v,"_id=? AND due=?",new String[]{Long.toString(id),Long.toString(due)});
    }
}
