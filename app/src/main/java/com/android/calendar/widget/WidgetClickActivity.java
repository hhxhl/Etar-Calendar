package com.android.calendar.widget;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import com.android.calendar.AllInOneActivity;
import com.android.calendar.EventInfoActivity;
import com.android.calendar.tasks.TaskListActivity;

/** Explicit destination for mutable collection widget PendingIntents. */
public final class WidgetClickActivity extends Activity {
    static PendingIntent launchTemplate(Context context) {
        Intent launch = new Intent(context, WidgetClickActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    static Intent itemIntent(long taskId, long date) {
        Intent intent = new Intent();
        if (taskId > 0) {
            intent.setData(Uri.parse("etar-task:" + taskId));
        } else {
            intent.setData(Uri.parse("content://com.android.calendar/time/" + date));
        }
        return intent;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Uri data = getIntent().getData();
            if (data == null) return;
            if ("etar-task".equals(data.getScheme())) {
                long id = Long.parseLong(data.getSchemeSpecificPart());
                if (id > 0) {
                    startActivity(new Intent(this, TaskListActivity.class)
                            .putExtra("task_id", id));
                }
                return;
            }
            if (!"content".equals(data.getScheme())
                    || !"com.android.calendar".equals(data.getAuthority())) return;
            java.util.List<String> parts = data.getPathSegments();
            boolean event = parts.size() == 2 && "events".equals(parts.get(0));
            boolean date = parts.size() == 2 && "time".equals(parts.get(0));
            boolean empty = parts.size() == 1 && "events".equals(parts.get(0));
            if (!event && !date && !empty) return;
            Intent launch = new Intent(Intent.ACTION_VIEW, data, this,
                    event ? EventInfoActivity.class : AllInOneActivity.class);
            if (getIntent().getExtras() != null) launch.putExtras(getIntent().getExtras());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
            startActivity(launch);
        } catch (NumberFormatException ignored) {
            // Ignore a stale or malformed widget row.
        } finally {
            finish();
        }
    }
}
