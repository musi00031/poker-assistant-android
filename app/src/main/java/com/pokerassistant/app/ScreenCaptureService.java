package com.pokerassistant.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class ScreenCaptureService extends Service {

    public static final String ACTION_READY =
            "com.pokerassistant.app.SCREEN_CAPTURE_SERVICE_READY";

    private static final String CHANNEL_ID = "poker_screen_capture";
    private static final int NOTIFICATION_ID = 7002;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Poker Assistant")
                .setContentText("Захват экрана активен")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            Intent ready = new Intent(ACTION_READY);
            ready.setPackage(getPackageName());
            sendBroadcast(ready);

        } catch (Exception e) {
            Intent failed = new Intent(ACTION_READY);
            failed.setPackage(getPackageName());
            failed.putExtra("capture_service_error", e.toString());
            sendBroadcast(failed);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Захват экрана",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription(
                    "Уведомление во время захвата экрана Poker Assistant"
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
