package com.pokerassistant.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class CalibrationActivity extends Activity {
    private static final int REQ = 8127;
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(7,12,22));
        getWindow().setNavigationBarColor(Color.rgb(7,12,22));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(dp(18), dp(24), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(7,12,22));

        TextView title = new TextView(this);
        title.setText("SCREEN CALIBRATION / PRO");
        title.setTextColor(Color.rgb(65,230,255));
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(.035f);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView info = new TextView(this);
        info.setText("Настрой области прямо поверх ClubGG. Перетаскивай прямоугольник, чтобы переместить его; тяни за яркий нижний правый угол, чтобы изменить размер. Кнопками снизу переключай левую карту, правую карту и общие карты. Нажми СОХРАНИТЬ — координаты сохранятся для следующих запусков.");
        info.setTextColor(Color.rgb(223,232,246));
        info.setTextSize(16);
        info.setLineSpacing(dp(3), 1.0f);
        info.setPadding(0, dp(18), 0, dp(24));
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        Button start = new Button(this);
        start.setText("ЗАПУСТИТЬ КАЛИБРОВКУ");
        start.setAllCaps(false);
        start.setTextSize(15);
        start.setTypeface(Typeface.DEFAULT_BOLD);
        start.setMinHeight(dp(58));
        start.setMinimumHeight(dp(58));
        start.setPadding(dp(12), dp(8), dp(12), dp(8));
        start.setTextColor(Color.rgb(4,15,24));
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(65,230,255)));
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(-1, dp(58));
        buttonLp.topMargin = dp(4);
        root.addView(start, buttonLp);

        TextView hint = new TextView(this);
        hint.setText("Сначала открой ClubGG, затем вернись сюда и запусти настройку поверх игры.");
        hint.setTextColor(Color.rgb(130,155,178));
        hint.setTextSize(13);
        hint.setPadding(0, dp(14), 0, 0);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ);
            } else {
                launch();
            }
        });
        setContentView(root);
    }

    @Override protected void onActivityResult(int q, int r, Intent d) {
        super.onActivityResult(q, r, d);
        if (q == REQ) {
            if (Settings.canDrawOverlays(this)) launch();
            else android.widget.Toast.makeText(this, "Разреши отображение поверх других приложений и вернись сюда", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void launch() {
        if (!Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this, "Нет разрешения на отображение поверх приложений", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startService(new Intent(this, CalibrationOverlayService.class));
            finish();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Не удалось запустить калибровку: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}