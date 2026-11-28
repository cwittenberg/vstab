package app.vstab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps the user's on-device IDE process attached to an explicit Android service. */
public final class LocalRuntimeService extends Service {
    private static final int NOTIFICATION = 74;
    private static final String CHANNEL = "vstab-local-runtime";
    private LocalRuntimeManager runtime;

    @Override public void onCreate() {
        super.onCreate();
        runtime = ((VstabApplication) getApplication()).runtime();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "On-device development environment", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "app.vstab.STOP_LOCAL_RUNTIME".equals(intent.getAction())) {
            runtime.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY;
        }
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, LocalRuntimeService.class).setAction("app.vstab.STOP_LOCAL_RUNTIME");
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_vstab)
                .setContentTitle("vstab local development environment")
                .setContentText("Your Linux environment, extensions and terminal tools are running on this device")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopAction).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, notification);
        runtime.start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { runtime.stop(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
