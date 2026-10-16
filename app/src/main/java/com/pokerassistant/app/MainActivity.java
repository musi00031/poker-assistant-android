package com.pokerassistant.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.Locale;

public class MainActivity extends Activity {

    private final int BG = Color.rgb(12, 12, 14);
    private final int PANEL = Color.rgb(27, 27, 31);
    private final int SILVER = Color.rgb(205, 205, 215);
    private final int MUTED = Color.rgb(145, 145, 155);
    private final int WHITE = Color.WHITE;
    private static final int SCREEN_CAPTURE_REQUEST = 7001;

    private EditText potInput;
    private EditText betInput;
    private EditText callInput;
    private EditText equityInput;
    private TextView result;

    private MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler handler;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private boolean frameCaptured = false;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handler = new Handler(Looper.getMainLooper());
        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        screenDensity = getResources().getDisplayMetrics().densityDpi;

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

        addDivider(root);

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

        Button calculate = makeButton("РАССЧИТАТЬ");
        LinearLayout.LayoutParams calculateParams =
                new LinearLayout.LayoutParams(-1, dp(54));
        calculateParams.setMargins(0, dp(20), 0, dp(12));
        root.addView(calculate, calculateParams);

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
                "Расчёт использует введённое эквити. Он не учитывает автоматически диапазоны соперников и будущие ставки.");
        note.setTextColor(MUTED);
        note.setTextSize(12);
        note.setPadding(0, dp(14), 0, dp(4));
        root.addView(note);

        addDivider(root);

        TextView captureTitle = new TextView(this);
        captureTitle.setText("АНАЛИЗ ИЗОБРАЖЕНИЯ");
        captureTitle.setTextColor(SILVER);
        captureTitle.setTextSize(12);
        captureTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(captureTitle);

        TextView captureInfo = new TextView(this);
        captureInfo.setText(
                "Захват позволяет проверить изображение стола. Сначала Android запросит разрешение. Кадр останется внутри приложения.");
        captureInfo.setTextColor(MUTED);
        captureInfo.setTextSize(13);
        captureInfo.setPadding(0, dp(8), 0, dp(12));
        root.addView(captureInfo);

        Button capture = makeButton("ЗАХВАТИТЬ ЭКРАН");
        root.addView(capture, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView privacy = new TextView(this);
        privacy.setText("Захват запускается только после твоего подтверждения.");
        privacy.setTextColor(MUTED);
        privacy.setTextSize(11);
        privacy.setPadding(0, dp(10), 0, 0);
        root.addView(privacy);

        calculate.setOnClickListener(v -> calculate());
        capture.setOnClickListener(v -> requestScreenCapture());

        setContentView(scroll);
        calculate();
    }

    private void addDivider(LinearLayout root) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(65, 65, 72));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(0, dp(22), 0, dp(22));
        root.addView(divider, params);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.BLACK);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(SILVER));
        return button;
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

    private void requestScreenCapture() {
        if (projectionManager == null) {
            Toast.makeText(this, "Захват экрана недоступен", Toast.LENGTH_LONG).show();
            return;
        }
        frameCaptured = false;
        startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                SCREEN_CAPTURE_REQUEST);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != SCREEN_CAPTURE_REQUEST) return;

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Захват отменён", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            projection = projectionManager.getMediaProjection(resultCode, data);
            if (projection == null) {
                Toast.makeText(this, "Не удалось начать захват", Toast.LENGTH_LONG).show();
                return;
            }

            imageReader = ImageReader.newInstance(
                    screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    releaseCaptureResources();
                }
            }, handler);

            virtualDisplay = projection.createVirtualDisplay(
                    "PokerAssistantCapture",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler);

            handler.postDelayed(this::readCapturedFrame, 900);

        } catch (Exception e) {
            releaseCaptureResources();
            Toast.makeText(this,
                    "Ошибка захвата: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void readCapturedFrame() {
        if (imageReader == null || frameCaptured) return;

        Image image = null;
        Bitmap bitmap = null;

        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                handler.postDelayed(this::readCapturedFrame, 400);
                return;
            }

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap padded = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888);

            padded.copyPixelsFromBuffer(buffer);
            bitmap = Bitmap.createBitmap(
                    padded, 0, 0, screenWidth, screenHeight);
            padded.recycle();

            frameCaptured = true;
            showCapturedImage(bitmap);
            bitmap = null;

        } catch (Exception e) {
            Toast.makeText(this,
                    "Не удалось прочитать кадр: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (image != null) image.close();
            releaseCaptureResources();
        }
    }

    private void showCapturedImage(Bitmap bitmap) {
        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(bitmap);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(Color.BLACK);

        TextView heading = new TextView(this);
        heading.setText("ЗАХВАЧЕННЫЙ КАДР");
        heading.setTextColor(SILVER);
        heading.setTextSize(13);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView explanation = new TextView(this);
        explanation.setText(
                "Это проверочный кадр. Автоматическое распознавание карт и ставок пока не подключено.");
        explanation.setTextColor(MUTED);
        explanation.setTextSize(12);
        explanation.setPadding(dp(16), dp(8), dp(16), dp(16));

        Button close = makeButton("ЗАКРЫТЬ");
        close.setOnClickListener(v -> finishCapturePreview());

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setBackgroundColor(BG);
        preview.addView(heading);
        preview.addView(imageView, new LinearLayout.LayoutParams(
                -1, 0, 1f));
        preview.addView(explanation);
        preview.addView(close, new LinearLayout.LayoutParams(
                -1, dp(52)));

        setContentView(preview);
    }

    private void finishCapturePreview() {
        recreate();
    }

    private void releaseCaptureResources() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    @Override
    protected void onDestroy() {
        releaseCaptureResources();
        super.onDestroy();
    }
}
