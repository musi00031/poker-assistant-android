package com.pokerassistant.app;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

    private TextView heroText;
    private TextView boardText;
    private TextView statusText;
    private TextView resultText;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            updateHud();
            handler.postDelayed(this, 1000);
        }
    };

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable background(int color, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView makeText(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(dp(4), dp(3), dp(4), dp(3));
        return view;
    }

    private void addSection(LinearLayout parent, String title,
                            TextView content) {
        TextView heading = makeText(title, 10, Color.rgb(155, 155, 170));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setLetterSpacing(0.08f);
        parent.addView(heading);
        parent.addView(content);
    }

    private SharedPreferences layoutPrefs() {
        return getSharedPreferences("poker_overlay_layout", MODE_PRIVATE);
    }

    private void saveLayout() {
        if (windowParams == null) return;
        layoutPrefs().edit()
                .putInt("x", windowParams.x)
                .putInt("y", windowParams.y)
                .putInt("width", windowParams.width)
                .putInt("height", windowParams.height)
                .apply();
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
                Color.rgb(14, 14, 17),
                Color.rgb(145, 145, 160)
        ));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = makeText("POKER ASSISTANT", 12,
                Color.rgb(225, 225, 233));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(20);
        close.setTextColor(Color.WHITE);
        close.setAllCaps(false);
        close.setPadding(0, 0, 0, 0);
        close.setBackgroundColor(Color.TRANSPARENT);
        header.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));

        panel.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(105, 105, 118));
        LinearLayout.LayoutParams dividerParams =
                new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(0, dp(5), 0, dp(9));
        panel.addView(divider, dividerParams);

        heroText = makeText("Карты не выбраны", 17,
                Color.rgb(235, 235, 242));
        boardText = makeText("—", 17,
                Color.rgb(235, 235, 242));
        statusText = makeText("Ожидание данных", 11,
                Color.rgb(220, 100, 100));
        resultText = makeText(
                "Комбинация: —\nЭквити: —\n\nБанк: не распознан\n" +
                "Ставка: не распознана\nКолл: не распознан\n" +
                "Шансы банка: —\nEV: —",
                12, Color.rgb(220, 220, 228));
        resultText.setLineSpacing(dp(3), 1f);

        addSection(panel, "ВАШИ КАРТЫ", heroText);
        addSection(panel, "КАРТЫ СТОЛА", boardText);

        View divider2 = new View(this);
        divider2.setBackgroundColor(Color.rgb(65, 65, 75));
        LinearLayout.LayoutParams divider2Params =
                new LinearLayout.LayoutParams(-1, dp(1));
        divider2Params.setMargins(0, dp(8), 0, dp(8));
        panel.addView(divider2, divider2Params);

        addSection(panel, "МАТЕМАТИКА", resultText);
        panel.addView(statusText);

        // Уголок для изменения размера плавающего окна.
        LinearLayout resizeRow = new LinearLayout(this);
        resizeRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);

        TextView resizeGrip = makeText("◢", 20,
                Color.rgb(200, 200, 215));
        resizeGrip.setGravity(Gravity.CENTER);
        resizeGrip.setContentDescription("Изменить размер окна");

        resizeRow.addView(resizeGrip,
                new LinearLayout.LayoutParams(dp(36), dp(32)));
        panel.addView(resizeRow,
                new LinearLayout.LayoutParams(-1, dp(32)));


        SharedPreferences savedLayout = layoutPrefs();

        windowParams = new WindowManager.LayoutParams(
                savedLayout.getInt("width", dp(280)),
                savedLayout.getInt(
                        "height", WindowManager.LayoutParams.WRAP_CONTENT),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = savedLayout.getInt("x", dp(12));
        windowParams.y = savedLayout.getInt("y", dp(100));

        overlayView = panel;

        try {
            windowManager.addView(overlayView, windowParams);
        } catch (Exception e) {
            overlayView = null;
            stopSelf();
            return;
        }

        close.setOnClickListener(v -> stopSelf());

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
                        windowParams.x = startX
                                + (int) (event.getRawX() - touchX);
                        windowParams.y = startY
                                + (int) (event.getRawY() - touchY);

                        try {
                            if (overlayView != null) {
                                windowManager.updateViewLayout(
                                        overlayView, windowParams);
                            }
                        } catch (Exception ignored) {
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        saveLayout();
                        return true;

                    default:
                        return false;
                }
            }
        });

        resizeGrip.setOnTouchListener(new View.OnTouchListener() {
            private int startWidth;
            private int startHeight;
            private float touchX;
            private float touchY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startWidth = overlayView.getWidth();
                        startHeight = overlayView.getHeight();
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int screenWidth = getResources()
                                .getDisplayMetrics().widthPixels;
                        int screenHeight = getResources()
                                .getDisplayMetrics().heightPixels;

                        int newWidth = startWidth
                                + (int) (event.getRawX() - touchX);
                        int newHeight = startHeight
                                + (int) (event.getRawY() - touchY);

                        windowParams.width = Math.max(dp(220),
                                Math.min(newWidth, screenWidth - dp(16)));
                        windowParams.height = Math.max(dp(160),
                                Math.min(newHeight, screenHeight - dp(40)));

                        try {
                            if (overlayView != null) {
                                windowManager.updateViewLayout(
                                        overlayView, windowParams);
                            }
                        } catch (Exception ignored) {
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveLayout();
                        return true;

                    default:
                        return true;
                }
            }
        });

        updateHud();
        handler.postDelayed(refreshTask, 1000);
    }

    private void updateHud() {
        if (heroText == null || boardText == null
                || resultText == null || statusText == null) {
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences("poker_hud", MODE_PRIVATE);

        heroText.setText(prefs.getString("hero", "Карты не выбраны"));
        boardText.setText(prefs.getString("board", "—"));
        resultText.setText(prefs.getString(
                "result",
                "Комбинация: —\nЭквити: —\n\nБанк: не распознан\n" +
                "Ставка: не распознана\nКолл: не распознан\n" +
                "Шансы банка: —\nEV: —"
        ));
        String baseStatus = prefs.getString(
                "status", "Ожидание данных"
        );
        String ocrStatus = prefs.getString("ocr_status", "");
        String ocrText = prefs.getString("ocr_text", "");

        if (!ocrStatus.isEmpty()) {
            baseStatus = baseStatus + "\n" + ocrStatus;
        }

        statusText.setText(baseStatus);

        if (!ocrText.isEmpty()) {
            android.util.Log.d("PokerAssistantOCR", "HUD OCR: " + ocrText);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        updateHud();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refreshTask);

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
