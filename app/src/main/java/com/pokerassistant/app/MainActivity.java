package com.pokerassistant.app;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

public class MainActivity extends Activity {

    private final int BG = Color.rgb(12, 12, 14);
    private final int PANEL = Color.rgb(27, 27, 31);
    private final int SILVER = Color.rgb(205, 205, 215);
    private final int MUTED = Color.rgb(145, 145, 155);
    private final int WHITE = Color.WHITE;

    private EditText potInput;
    private EditText betInput;
    private EditText callInput;
    private EditText equityInput;
    private TextView result;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(root);

        TextView brand = new TextView(this);
        brand.setText("POKER ASSISTANT");
        brand.setTextColor(SILVER);
        brand.setTextSize(12);
        brand.setLetterSpacing(0.18f);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(brand);

        TextView title = new TextView(this);
        title.setText("Покерный\nкалькулятор");
        title.setTextColor(WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(18), 0, dp(6));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Расчёт банка и математической выгодности колла");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(14);
        root.addView(subtitle);

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(65, 65, 72));
        LinearLayout.LayoutParams dividerParams =
                new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(0, dp(22), 0, dp(22));
        root.addView(divider, dividerParams);

        TextView section = new TextView(this);
        section.setText("ПАРАМЕТРЫ РАЗДАЧИ");
        section.setTextColor(SILVER);
        section.setTextSize(12);
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(section);

        potInput = addInput(root, "Текущий банк", "1000");
        betInput = addInput(root, "Ставка соперника", "500");
        callInput = addInput(root, "Сколько нужно доплатить", "500");
        equityInput = addInput(root, "Твоя оценка эквити, %", "30");

        Button calculate = new Button(this);
        calculate.setText("РАССЧИТАТЬ");
        calculate.setTextColor(Color.BLACK);
        calculate.setTextSize(14);
        calculate.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        calculate.setAllCaps(false);
        calculate.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(SILVER));

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(-1, dp(54));
        buttonParams.setMargins(0, dp(20), 0, dp(18));
        root.addView(calculate, buttonParams);

        LinearLayout resultPanel = new LinearLayout(this);
        resultPanel.setOrientation(LinearLayout.VERTICAL);
        resultPanel.setPadding(dp(17), dp(17), dp(17), dp(17));
        resultPanel.setBackgroundColor(PANEL);
        root.addView(resultPanel);

        TextView resultHeading = new TextView(this);
        resultHeading.setText("РЕЗУЛЬТАТ");
        resultHeading.setTextColor(SILVER);
        resultHeading.setTextSize(12);
        resultHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        resultPanel.addView(resultHeading);

        result = new TextView(this);
        result.setTextColor(WHITE);
        result.setTextSize(16);
        result.setLineSpacing(dp(5), 1f);
        result.setPadding(0, dp(12), 0, dp(4));
        resultPanel.addView(result);

        TextView note = new TextView(this);
        note.setText(
                "Эквити вводится вручную. Для точного решения учитывай диапазон соперника, позиции и размер банка.");
        note.setTextColor(MUTED);
        note.setTextSize(12);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        calculate.setOnClickListener(v -> calculate());

        setContentView(scroll);
        calculate();
    }

    private EditText addInput(LinearLayout root, String label, String value) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(SILVER);
        text.setTextSize(13);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(-1, -2);
        labelParams.setMargins(0, dp(15), 0, dp(7));
        root.addView(text, labelParams);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setTextSize(16);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(SILVER));
        input.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);

        root.addView(input, new LinearLayout.LayoutParams(-1, dp(48)));
        return input;
    }

    private double number(EditText field) {
        String value = field.getText().toString().trim().replace(',', '.');
        if (value.isEmpty()) return 0;
        return Double.parseDouble(value);
    }

    private void calculate() {
        try {
            double pot = number(potInput);
            double bet = number(betInput);
            double call = number(callInput);
            double equity = number(equityInput);

            if (pot < 0 || bet < 0 || call < 0 ||
                    equity < 0 || equity > 100) {
                throw new IllegalArgumentException();
            }

            double finalPot = pot + bet + call;
            double required = finalPot > 0 ? call / finalPot * 100 : 0;
            double ev = equity / 100.0 * finalPot - call;

            String decision;
            if (call == 0) {
                decision = "Доплаты нет";
            } else if (equity > required) {
                decision = "Колл выгоден по введённому эквити";
            } else if (Math.abs(equity - required) < 0.000001) {
                decision = "Пограничное решение";
            } else {
                decision = "Эквити недостаточно для колла";
            }

            result.setText(String.format(
                    Locale.getDefault(),
                    "Итоговый банк: %.2f\n" +
                    "Необходимое эквити: %.2f%%\n" +
                    "Твоё эквити: %.2f%%\n" +
                    "Ожидаемое значение колла: %.2f\n\n%s",
                    finalPot, required, equity, ev, decision));

        } catch (Exception e) {
            result.setText("Проверь введённые числа.");
        }
    }
}
