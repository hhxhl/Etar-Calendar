package com.android.calendar.tasks;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Account-scoped, signature-protected sync endpoint. TaskStore remains the local data source. */
public final class TaskProvider extends ContentProvider {
    private TaskStore store;
    private final ThreadLocal<Changes> transaction = new ThreadLocal<>();

    @Override public boolean onCreate() {
        store = TaskStore.get(Objects.requireNonNull(getContext()));
        return true;
    }

    private static final class Route {
        final String table;
        final Long id;
        final String key;
        Route(Uri uri) {
            List<String> path = uri.getPathSegments();
            if (path.size() < 1 || path.size() > 2) throw new IllegalArgumentException("Invalid URI");
            table = path.get(0);
            if (!table.equals(TaskContract.TASK_LISTS) && !table.equals(TaskContract.TASKS)
                    && !table.equals(TaskContract.PROPERTIES)) throw new IllegalArgumentException("Invalid table");
            key = table.equals(TaskContract.PROPERTIES) ? "property_id" : "_id";
            id = path.size() == 2 ? Long.valueOf(path.get(1)) : null;
        }
    }

    private void trustedCaller() {
        if (!TaskContract.DAVX5_PACKAGE.equals(getCallingPackage()))
            throw new SecurityException("Only DAVx5 may access synced tasks");
        // The exported provider additionally requires the caller to share Etar's signing certificate.
    }

    private String accountScope(Uri uri) {
        trustedCaller();
        String name = uri.getQueryParameter(TaskContract.ACCOUNT_NAME);
        String type = uri.getQueryParameter(TaskContract.ACCOUNT_TYPE);
        if (!uri.getBooleanQueryParameter(TaskContract.SYNC_ADAPTER, false)
                || name == null || name.isEmpty() || type == null || type.isEmpty()
                || TaskContract.LOCAL_ACCOUNT.equals(type))
            throw new SecurityException("A non-local sync account is required");
        return "account_name=" + DatabaseUtils.sqlEscapeString(name)
                + " AND account_type=" + DatabaseUtils.sqlEscapeString(type);
    }

    private String scope(Route route, Uri uri) {
        String account = accountScope(uri);
        String lists = "SELECT _id FROM tasklists WHERE " + account;
        String condition = route.table.equals(TaskContract.TASK_LISTS) ? account
                : route.table.equals(TaskContract.TASKS) ? "list_id IN (" + lists + ")"
                : "task_id IN (SELECT _id FROM tasks WHERE list_id IN (" + lists + "))";
        if (route.id != null) condition += " AND " + route.key + "=" + route.id;
        return condition;
    }

    private static Map<String,String> columns(SQLiteDatabase db, String table) {
        Map<String,String> columns = new LinkedHashMap<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                columns.put(name, name);
            }
        }
        return columns;
    }

    private SQLiteQueryBuilder builder(SQLiteDatabase db, Route route, Uri uri, boolean read) {
        Map<String,String> columns = columns(db, route.table);
        String table = route.table;
        if (read && route.table.equals(TaskContract.TASKS)) {
            String sql = "SELECT t.*,l.account_name,l.account_type,l.list_name,l.list_color,l.list_owner,l.list_access_level,l.visible FROM tasks t JOIN tasklists l ON l._id=t.list_id";
            for (String key : new String[]{"account_name","account_type","list_name","list_color","list_owner","list_access_level","visible"})
                columns.put(key,key);
            if (uri.getBooleanQueryParameter(TaskContract.LOAD_PROPERTIES, false)) {
                StringBuilder properties = new StringBuilder();
                for (String key : columns(db, TaskContract.PROPERTIES).keySet()) {
                    properties.append(",p.").append(key);
                    columns.put(key,key);
                }
                sql = sql.replace(" FROM tasks t", properties + " FROM tasks t")
                        + " LEFT JOIN properties p ON p.task_id=t._id";
            }
            table = "(" + sql + ") AS task_rows";
        }
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(table);
        qb.setProjectionMap(columns);
        qb.setStrict(true);
        qb.setStrictColumns(true);
        qb.setStrictGrammar(true);
        qb.appendWhere(scope(route,uri));
        return qb;
    }

    private static ContentValues validated(SQLiteDatabase db, String table, ContentValues source) {
        ContentValues values = new ContentValues(Objects.requireNonNull(source));
        Map<String,String> columns = columns(db,table);
        for (String key : values.keySet())
            if (!columns.containsKey(key)) throw new IllegalArgumentException("Unknown column: " + key);
        return values;
    }

    private boolean hasRow(SQLiteDatabase db, Uri uri, String table, long id) {
        Uri target = ContentUris.withAppendedId(TaskContract.uri(getContext(),table),id)
                .buildUpon().encodedQuery(uri.getEncodedQuery()).build();
        Route route = new Route(target);
        try (Cursor cursor = builder(db,route,target,true).query(db,new String[]{route.key},null,null,null,null,null)) {
            return cursor.moveToFirst();
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        SQLiteDatabase db = store.getReadableDatabase();
        Cursor cursor = builder(db,new Route(uri),uri,true).query(db,projection,selection,args,null,null,order);
        cursor.setNotificationUri(Objects.requireNonNull(getContext()).getContentResolver(), uri);
        return cursor;
    }

    @Override public String getType(Uri uri) {
        Route route = new Route(uri);
        return (route.id == null ? "vnd.android.cursor.dir/" : "vnd.android.cursor.item/")
                + "vnd.ws.xsoh.etar." + route.table;
    }

    private final class Changes {
        final SQLiteDatabase db;
        final Map<Long,TaskStore.Task> before = new LinkedHashMap<>();
        final List<Long> dismiss = new ArrayList<>();
        boolean changed;
        Changes(SQLiteDatabase db) { this.db=db; }
        void capture(long id) {
            if (!before.containsKey(id)) before.put(id, store.find(id));
        }
        void reconcile() {
            for (Map.Entry<Long,TaskStore.Task> entry : before.entrySet())
                if (store.reconcileSyncedTask(db,entry.getKey(),entry.getValue())) dismiss.add(entry.getKey());
        }
        void publish() {
            if (!changed) return;
            for (long id : dismiss) TaskAlarmReceiver.dismiss(getContext(),id);
            getContext().getContentResolver().notifyChange(TaskContract.uri(getContext(),TaskContract.TASKS),null,0);
            getContext().getContentResolver().notifyChange(TaskContract.uri(getContext(),TaskContract.TASK_LISTS),null,0);
            store.notifyChanged(!before.isEmpty());
        }
    }

    private interface Mutation<T> { T run(Changes changes); }
    private <T> T mutate(Mutation<T> mutation) {
        Changes existing = transaction.get();
        if (existing != null) return mutation.run(existing);
        SQLiteDatabase db = store.getWritableDatabase();
        Changes changes = new Changes(db);
        db.beginTransaction();
        transaction.set(changes);
        T result;
        try {
            result = mutation.run(changes);
            changes.reconcile();
            db.setTransactionSuccessful();
        } finally {
            transaction.remove();
            db.endTransaction();
        }
        changes.publish();
        return result;
    }

    private void captureRows(Changes changes, Route route, Uri uri, String selection, String[] args, boolean deleting) {
        String field = route.table.equals(TaskContract.PROPERTIES) ? "task_id" : "_id";
        if (route.table.equals(TaskContract.TASK_LISTS) && !deleting) return;
        try (Cursor cursor = builder(changes.db,route,uri,true).query(changes.db,new String[]{field},selection,args,null,null,null)) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                if (!route.table.equals(TaskContract.TASK_LISTS)) changes.capture(id);
                else try (Cursor tasks = changes.db.query("tasks",new String[]{"_id"},"list_id=?",new String[]{Long.toString(id)},null,null,null)) {
                    while (tasks.moveToNext()) changes.capture(tasks.getLong(0));
                }
            }
        }
    }

    @Override public Uri insert(Uri uri, ContentValues supplied) {
        Route route = new Route(uri);
        if (route.id != null) throw new IllegalArgumentException("Collection URI required");
        accountScope(uri);
        return mutate(changes -> {
            ContentValues values = validated(changes.db,route.table,supplied);
            values.remove(route.key);
            switch (route.table) {
                case TaskContract.TASK_LISTS:
                    values.put("account_name",uri.getQueryParameter(TaskContract.ACCOUNT_NAME));
                    values.put("account_type",uri.getQueryParameter(TaskContract.ACCOUNT_TYPE));
                    if (values.getAsString("list_name") == null) values.put("list_name","");
                    break;
                case TaskContract.TASKS:
                    Long list = values.getAsLong("list_id");
                    if (list == null || !hasRow(changes.db,uri,TaskContract.TASK_LISTS,list))
                        throw new SecurityException("Task list is outside the sync account");
                    if (values.getAsString("_uid") == null) values.put("_uid",UUID.randomUUID().toString());
                    values.put("_dirty",0);
                    values.put("_deleted",0);
                    break;
                case TaskContract.PROPERTIES:
                    Long task = values.getAsLong("task_id");
                    if (task == null || !hasRow(changes.db,uri,TaskContract.TASKS,task))
                        throw new SecurityException("Task is outside the sync account");
                    changes.capture(task);
                    break;
            }
            long id = changes.db.insertOrThrow(route.table,null,values);
            if (route.table.equals(TaskContract.TASKS)) changes.before.put(id,null);
            changes.changed=true;
            return ContentUris.withAppendedId(TaskContract.uri(getContext(),route.table),id);
        });
    }

    @Override public int update(Uri uri, ContentValues supplied, String selection, String[] args) {
        Route route = new Route(uri);
        trustedCaller();
        // DAVx5's account rename uses an unscoped tasks URI and writes the list's account name.
        if (route.table.equals(TaskContract.TASKS) && route.id == null
                && uri.getQueryParameter(TaskContract.ACCOUNT_NAME) == null
                && supplied.size() == 1 && supplied.containsKey("account_name")
                && "account_name=?".equals(selection) && args != null && args.length == 1) {
            return mutate(changes -> {
                int count = changes.db.update("tasklists",supplied,"account_name=? AND account_type<>?",
                        new String[]{args[0],TaskContract.LOCAL_ACCOUNT});
                changes.changed |= count > 0;
                return count;
            });
        }
        accountScope(uri);
        return mutate(changes -> {
            ContentValues values = validated(changes.db,route.table,supplied);
            values.remove(route.key);
            if (values.containsKey("account_name") || values.containsKey("account_type"))
                throw new SecurityException("Cannot reassign a sync account");
            if (values.containsKey("list_id") && !hasRow(changes.db,uri,TaskContract.TASK_LISTS,values.getAsLong("list_id")))
                throw new SecurityException("Task list is outside the sync account");
            if (values.containsKey("task_id") && !hasRow(changes.db,uri,TaskContract.TASKS,values.getAsLong("task_id")))
                throw new SecurityException("Task is outside the sync account");
            captureRows(changes,route,uri,selection,args,false);
            int count = builder(changes.db,route,uri,false).update(changes.db,values,selection,args);
            changes.changed |= count > 0;
            return count;
        });
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        Route route = new Route(uri);
        accountScope(uri);
        return mutate(changes -> {
            captureRows(changes,route,uri,selection,args,true);
            int count = builder(changes.db,route,uri,false).delete(changes.db,selection,args);
            changes.changed |= count > 0;
            return count;
        });
    }

    @Override public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        trustedCaller();
        if (transaction.get() != null) return super.applyBatch(operations);
        SQLiteDatabase db = store.getWritableDatabase();
        Changes changes = new Changes(db);
        db.beginTransaction();
        transaction.set(changes);
        ContentProviderResult[] results;
        try {
            results=super.applyBatch(operations);
            changes.reconcile();
            db.setTransactionSuccessful();
        } finally {
            transaction.remove();
            db.endTransaction();
        }
        changes.publish();
        return results;
    }
}
