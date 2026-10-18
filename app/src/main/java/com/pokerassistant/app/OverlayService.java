package com.pokerassistant.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class OverlayService extends Service {
    public static final String ACTION_TOGGLE = "com.pokerassistant.app.OVERLAY_TOGGLE";
    public static final String ACTION_UPDATE = "com.pokerassistant.app.OVERLAY_UPDATE";
    public static final String EXTRA_STATE = "overlay_state";

    private static final String CHANNEL_ID = "poker_overlay";
    private static final int NOTIFICATION_ID = 7003;
    private WindowManager windowManager;
    private View overlay;
    private TextView stateView;
    private WindowManager.LayoutParams params;
    private boolean expanded = false;

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_UPDATE.equals(intent.getAction())) {
            String state = intent.getStringExtra(EXTRA_STATE);
            if (state != null && stateView != null) stateView.setText(state);
            return START_NOT_STICKY;
        }

        if (overlay != null) {
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Poker Assistant")
                    .setContentText("Плавающая панель активна")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, 0);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            showOverlay();
        } catch (Exception error) {
            android.util.Log.e("PokerOverlay", "Не удалось показать панель", error);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void showOverlay() {
        if (overlay != null) return;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackgroundColor(Color.rgb(20, 20, 24));

        TextView heading = new TextView(this);
        heading.setText("POKER ASSISTANT  ×");
        heading.setTextColor(Color.rgb(210, 210, 220));
        heading.setTextSize(12);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(heading);

        stateView = new TextView(this);
        stateView.setText("Ожидание карт…\nОткрой стол ClubGG");
        stateView.setTextColor(Color.WHITE);
        stateView.setTextSize(14);
        stateView.setPadding(0, dp(8), 0, 0);
        panel.addView(stateView);

        int windowType = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                dp(230), WindowManager.LayoutParams.WRAP_CONTENT, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(110);

        heading.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = params.x; startY = params.y;
                    downX = event.getRawX(); downY = event.getRawY();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = startX + (int) (event.getRawX() - downX);
                    params.y = startY + (int) (event.getRawY() - downY);
                    try { windowManager.updateViewLayout(panel, params); }
                    catch (Exception ignored) {}
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    float dx = Math.abs(event.getRawX() - downX);
                    float dy = Math.abs(event.getRawY() - downY);
                    if (dx < dp(8) && dy < dp(8)) {
                        expanded = !expanded;
                        params.width = expanded
                                ? WindowManager.LayoutParams.MATCH_PARENT : dp(230);
                        heading.setText(expanded
                                ? "POKER ASSISTANT  ·  ПОЛНАЯ ПАНЕЛЬ  ×"
                                : "POKER ASSISTANT  ·  КОМПАКТНО  ×");
                        try { windowManager.updateViewLayout(panel, params); }
                        catch (Exception ignored) {}
                    }
                    return true;
                }
                return false;
            }
        });

        overlay = panel;
        windowManager.addView(overlay, params);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void removeOverlay() {
        if (overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
            overlay = null;
            stateView = null;
        }
        stopForeground(true);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Плавающая панель", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
