package com.android.calendar.tasks;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import androidx.core.app.NotificationCompat;
import ws.xsoh.etar.R;

/** One system alarm for the earliest outstanding local task; no resident service. */
public final class TaskAlarmReceiver extends BroadcastReceiver {
    private static final String FIRE="com.android.calendar.tasks.FIRE";
    private static final String COMPLETE="com.android.calendar.tasks.COMPLETE";
    public static final String CHANNEL="task_notifications";
    // Only system rescheduling broadcasts use this worker. It has no resident thread.
    private static final ThreadPoolExecutor RESCHEDULE_EXECUTOR = new ThreadPoolExecutor(
            0, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    public static void ensureChannel(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.tasks_title), NotificationManager.IMPORTANCE_HIGH));
    }
    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c,0,new Intent(c,TaskAlarmReceiver.class).setAction(FIRE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    public static synchronized void schedule(Context c) {
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi=alarm(c);
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            manager.cancel(pi);
            return;
        }
        Long next=TaskStore.get(c).nextAlarm();
        if(next==null){
            manager.cancel(pi);
            return;
        }
        // Setting the same PendingIntent replaces the existing alarm atomically.
        long when=Math.max(System.currentTimeMillis()+1000,next);
        try {
            if(manager.canScheduleExactAlarms())manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
        } catch(SecurityException e){manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);}
    }
    public static void dismiss(Context c,long id) {
        ((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel("task:"+id,1);
    }
    @Override public void onReceive(Context context,Intent intent) {
        String action = intent.getAction();
        if (!FIRE.equals(action) && !COMPLETE.equals(action)) {
            Context app = context.getApplicationContext();
            PendingResult pending = goAsync();
            RESCHEDULE_EXECUTOR.execute(() -> {
                try {
                    schedule(app);
                } catch (RuntimeException error) {
                    Log.e("TaskAlarmReceiver", "Could not reschedule task alarm", error);
                } finally {
                    pending.finish();
                }
            });
            return;
        }
        TaskStore store=TaskStore.get(context);
        if(COMPLETE.equals(intent.getAction())){
            long id=intent.getLongExtra("id",0);
            store.completeAlarm(id, intent.getLongExtra("due", Long.MIN_VALUE));
            return; // Successful completion already reschedules the alarm.
        } else if(FIRE.equals(intent.getAction())){
            if(Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED){
                NotificationManager nm=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
                ensureChannel(context);
                for(TaskStore.Task t:store.dueAlarms(System.currentTimeMillis())){
                    Long alarmTime = t.alarmTime();
                    if (alarmTime == null) continue;
                    Intent open=new Intent(context,TaskListActivity.class).setData(Uri.parse("etar-task:"+t.id)).putExtra("task_id",t.id);
                    PendingIntent view=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                    Intent done=new Intent(context,TaskAlarmReceiver.class).setAction(COMPLETE).setData(Uri.parse("etar-task:"+t.id+":"+alarmTime)).putExtra("id",t.id).putExtra("due",alarmTime.longValue());
                    PendingIntent complete=PendingIntent.getBroadcast(context,0,done,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                    NotificationCompat.Builder notification = new NotificationCompat.Builder(context,CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(t.title).setContentText(t.notes)
                        .setContentIntent(view).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
                    if (t.writable) notification.addAction(0,context.getString(R.string.tasks_complete),complete);
                    nm.notify("task:"+t.id,1,notification.build());
                    store.markFired(t.id,alarmTime);
                }
            }
        }
        schedule(context);
    }
}
