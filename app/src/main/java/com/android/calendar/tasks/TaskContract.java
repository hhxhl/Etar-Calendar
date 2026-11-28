package com.android.calendar.tasks;

import android.content.Context;
import android.net.Uri;

/** DMFS-compatible subset used by the DAVx5 task backend. */
public final class TaskContract {
    private TaskContract() {}
    public static final String LOCAL_ACCOUNT = "org.dmfs.account.LOCAL";
    public static final String SYNC_ADAPTER = "caller_is_syncadapter";
    public static final String ACCOUNT_NAME = "account_name";
    public static final String ACCOUNT_TYPE = "account_type";
    public static final String LOAD_PROPERTIES = "load_properties";
    public static final String DAVX5_PACKAGE = "at.bitfire.davdroid";
    public static final String TASK_LISTS = "tasklists";
    public static final String TASKS = "tasks";
    public static final String PROPERTIES = "properties";
    public static Uri uri(Context context, String path) {
        return Uri.parse("content://" + context.getPackageName() + ".tasks/" + path);
    }
}
