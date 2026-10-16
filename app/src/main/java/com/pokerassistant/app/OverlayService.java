package com.pokerassistant.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OverlayService extends Service {

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams windowParams;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable background(int color, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.rgb(220, 220, 228));
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(background(
                Color.rgb(35, 35, 41),
                Color.rgb(105, 105, 118)
        ));
        return button;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackground(background(
                Color.rgb(15, 15, 18),
                Color.rgb(155, 155, 170)
        ));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("POKER ASSISTANT");
        title.setTextColor(Color.rgb(220, 220, 228));
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(4), dp(8), dp(8), dp(8));

        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        Button close = makeButton("×");
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(40)));

        panel.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(95, 95, 105));
        LinearLayout.LayoutParams dividerParams =
                new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(0, dp(4), 0, dp(10));
        panel.addView(divider, dividerParams);

        TextView status = new TextView(this);
        status.setText("Плавающее окно активно");
        status.setTextColor(Color.rgb(165, 165, 175));
        status.setTextSize(12);
        status.setPadding(dp(4), dp(4), dp(4), dp(12));
        panel.addView(status);

        Button open = makeButton("ОТКРЫТЬ КАЛЬКУЛЯТОР");
        panel.addView(open, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView hint = new TextView(this);
        hint.setText("Перетаскивай окно за заголовок.");
        hint.setTextColor(Color.rgb(125, 125, 138));
        hint.setTextSize(10);
        hint.setPadding(dp(4), dp(8), dp(4), 0);
        panel.addView(hint);

        windowParams = new WindowManager.LayoutParams(
                dp(260),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = dp(16);
        windowParams.y = dp(110);

        overlayView = panel;

        try {
            windowManager.addView(overlayView, windowParams);
        } catch (Exception e) {
            overlayView = null;
            stopSelf();
            return;
        }

        close.setOnClickListener(v -> stopSelf());

        open.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });

        title.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float touchX;
            private float touchY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = windowParams.x;
                        startY = windowParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        windowParams.x = startX + (int) (event.getRawX() - touchX);
                        windowParams.y = startY + (int) (event.getRawY() - touchY);

                        try {
                            if (overlayView != null) {
                                windowManager.updateViewLayout(overlayView, windowParams);
                            }
                        } catch (Exception ignored) {
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
