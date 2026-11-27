package com.android.calendar.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.Instant;
import java.time.LocalDate;
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
        super(context, "tasks.db", null, 1);
        appContext = context.getApplicationContext();
    }
    private void refreshWidgets() {
        com.android.calendar.Utils.sendUpdateWidgetIntent(appContext);
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasklists (_id INTEGER PRIMARY KEY, account_name TEXT NOT NULL DEFAULT 'Local', account_type TEXT NOT NULL DEFAULT 'org.dmfs.account.LOCAL', list_name TEXT NOT NULL, list_color INTEGER NOT NULL DEFAULT 0, visible INTEGER NOT NULL DEFAULT 1, sync_enabled INTEGER NOT NULL DEFAULT 0, _sync_id TEXT, sync_version TEXT)");
        db.execSQL("CREATE TABLE tasks (_id INTEGER PRIMARY KEY, list_id INTEGER NOT NULL REFERENCES tasklists(_id) ON DELETE CASCADE, _uid TEXT NOT NULL UNIQUE, _sync_id TEXT, sync_version TEXT, _dirty INTEGER NOT NULL DEFAULT 1, _deleted INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', due INTEGER, dtstart INTEGER, is_allday INTEGER NOT NULL DEFAULT 0, tz TEXT, status INTEGER NOT NULL DEFAULT 0, completed INTEGER, percent_complete INTEGER NOT NULL DEFAULT 0, priority INTEGER NOT NULL DEFAULT 0, rrule TEXT, created INTEGER NOT NULL, last_modified INTEGER NOT NULL, alarm_fired INTEGER)");
        db.execSQL("CREATE TABLE properties (property_id INTEGER PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES tasks(_id) ON DELETE CASCADE, mimetype TEXT NOT NULL, data0 INTEGER, data1 INTEGER, data2 TEXT, data3 INTEGER)");
        db.execSQL("CREATE INDEX task_due ON tasks(status, _deleted, due)");
        db.execSQL("CREATE INDEX task_list ON tasks(list_id)");
        db.execSQL("CREATE INDEX property_task ON properties(task_id)");
        db.execSQL("INSERT INTO tasklists(_id,list_name) VALUES(1,'')");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Missing task database migration");
    }
    public static final class TaskList {
        public long id;
        public String name;
        public int color;
        @Override public String toString() { return name; }
    }
    public static final class Task {
        public long id, listId = 1;
        public String title = "", notes = "", rule, tz;
        public Long due, anchor;
        public boolean allDay, done, alarm;
        public int priority;
    }
    public List<TaskList> lists() {
        ArrayList<TaskList> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("tasklists", new String[]{"_id","list_name","list_color"}, null,null,null,null,"_id")) {
            while (c.moveToNext()) {
                TaskList l = new TaskList(); l.id = c.getLong(0); l.name = c.getString(1); l.color = c.getInt(2); result.add(l);
            }
        }
        return result;
    }
    public long addList(String name) {
        ContentValues v = new ContentValues(); v.put("list_name",name.trim());
        long id = getWritableDatabase().insertOrThrow("tasklists",null,v);
        refreshWidgets();
        return id;
    }
    public void renameList(long id,String name) {
        ContentValues v = new ContentValues(); v.put("list_name",name.trim());
        getWritableDatabase().update("tasklists",v,"_id=?",new String[]{Long.toString(id)});
        refreshWidgets();
    }
    public void deleteList(long id) {
        if (id != 1 && getWritableDatabase().delete("tasklists","_id=?",new String[]{Long.toString(id)}) > 0) refreshWidgets();
    }
    private static final String SELECT = "SELECT t._id,t.list_id,t.title,t.description,t.due,t.dtstart,t.is_allday,t.tz,t.status,t.priority,t.rrule, EXISTS(SELECT 1 FROM properties p WHERE p.task_id=t._id AND p.mimetype='vnd.android.cursor.item/alarm') FROM tasks t WHERE t._deleted=0";
    private static Task read(Cursor c) {
        Task t = new Task(); t.id=c.getLong(0); t.listId=c.getLong(1); t.title=c.getString(2); t.notes=c.getString(3);
        t.due=c.isNull(4)?null:c.getLong(4); t.anchor=c.isNull(5)?null:c.getLong(5); t.allDay=c.getInt(6)!=0;
        t.tz=c.getString(7); t.done=c.getInt(8)==2; t.priority=c.getInt(9); t.rule=c.getString(10); t.alarm=c.getInt(11)!=0;
        return t;
    }
    public List<Task> tasks(long listId) {
        ArrayList<Task> result=new ArrayList<>();
        String filter=listId==0?"":" AND t.list_id=?";
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
    public Task find(long id) {
        try(Cursor c=getReadableDatabase().rawQuery(SELECT+" AND t._id=?",new String[]{Long.toString(id)})) {return c.moveToFirst()?read(c):null;}
    }
    public long save(Task t) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues v=new ContentValues();
            v.put("list_id",t.listId); v.put("title",t.title.trim()); v.put("description",t.notes);
            v.put("due",t.due); v.put("dtstart",t.anchor); v.put("is_allday",t.allDay?1:0); v.put("tz",t.tz);
            v.put("priority",t.priority); v.put("rrule",t.rule); v.put("last_modified",System.currentTimeMillis()); v.put("_dirty",1);
            v.putNull("alarm_fired");
            if(t.id==0) {v.put("_uid",UUID.randomUUID().toString()); v.put("created",System.currentTimeMillis()); t.id=db.insertOrThrow("tasks",null,v);}
            else db.update("tasks",v,"_id=?",new String[]{Long.toString(t.id)});
            db.delete("properties","task_id=? AND mimetype=?",new String[]{Long.toString(t.id),"vnd.android.cursor.item/alarm"});
            if(t.alarm && t.due!=null && !t.allDay) {
                ContentValues a=new ContentValues(); a.put("task_id",t.id); a.put("mimetype","vnd.android.cursor.item/alarm"); a.put("data0",0); a.put("data1",1); a.put("data3",1); db.insertOrThrow("properties",null,a);
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        refreshWidgets();
        return t.id;
    }
    public void delete(long id) {
        if (getWritableDatabase().delete("tasks","_id=?",new String[]{Long.toString(id)}) > 0) refreshWidgets();
    }
    /** Preset recurrence: preserve original day-of-month and wall-clock time. */
    static Long nextDue(Task t) {
        if(t.due==null || t.rule==null) return null;
        ZoneId zone=t.allDay?ZoneOffset.UTC:ZoneId.of(t.tz==null?ZoneId.systemDefault().getId():t.tz);
        ZonedDateTime current=Instant.ofEpochMilli(t.due).atZone(zone);
        ZonedDateTime anchor=Instant.ofEpochMilli(t.anchor==null?t.due:t.anchor).atZone(zone);
        ZonedDateTime next;
        switch(t.rule) {
            case "FREQ=DAILY": next=current.plusDays(1); break;
            case "FREQ=WEEKLY": next=current.plusWeeks(1); break;
            case "FREQ=MONTHLY":
                LocalDate month=current.toLocalDate().withDayOfMonth(1).plusMonths(1);
                next=month.withDayOfMonth(Math.min(anchor.getDayOfMonth(),month.lengthOfMonth())).atTime(anchor.toLocalTime()).atZone(zone); break;
            case "FREQ=YEARLY":
                LocalDate year=anchor.toLocalDate().plusYears((long)current.getYear()-anchor.getYear()+1);
                next=year.atTime(anchor.toLocalTime()).atZone(zone); break;
            default: return null;
        }
        return next.toInstant().toEpochMilli();
    }
    public void complete(long id,boolean complete) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Task t=find(id); if(t==null || t.done==complete){db.setTransactionSuccessful();return;}
            ContentValues v=new ContentValues(); Long next=complete?nextDue(t):null;
            if(next!=null) {v.put("due",next);v.put("status",0);v.put("percent_complete",0);v.putNull("completed");}
            else {v.put("status",complete?2:0);v.put("percent_complete",complete?100:0);if(complete)v.put("completed",System.currentTimeMillis());else v.putNull("completed");}
            v.put("last_modified",System.currentTimeMillis());v.put("_dirty",1);v.putNull("alarm_fired");
            db.update("tasks",v,"_id=?",new String[]{Long.toString(id)});db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        refreshWidgets();
    }
    private static final String ALARMS=" FROM tasks t WHERE t._deleted=0 AND t.status=0 AND t.is_allday=0 AND t.due IS NOT NULL AND t.alarm_fired IS NULL AND EXISTS(SELECT 1 FROM properties p WHERE p.task_id=t._id AND p.mimetype='vnd.android.cursor.item/alarm')";
    public Long nextAlarm() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT MIN(t.due)"+ALARMS,null)){return c.moveToFirst()&&!c.isNull(0)?c.getLong(0):null;}
    }
    public List<Task> dueAlarms(long now) {
        ArrayList<Task> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT t._id"+ALARMS+" AND t.due<=?",new String[]{Long.toString(now)})){
            while(c.moveToNext()){Task t=find(c.getLong(0));if(t!=null)result.add(t);}
        }return result;
    }
    public void markFired(long id,long due) {
        ContentValues v=new ContentValues();v.put("alarm_fired",due);
        getWritableDatabase().update("tasks",v,"_id=? AND due=?",new String[]{Long.toString(id),Long.toString(due)});
    }
}
