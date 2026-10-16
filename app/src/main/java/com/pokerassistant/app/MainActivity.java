package com.pokerassistant.app;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.ArrayList;
import java.util.List;
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

    // CAPTURE_SERVICE_LINK_V1
    private android.content.BroadcastReceiver captureServiceReceiver;
    private int pendingCaptureResultCode;
    private Intent pendingCaptureData;
    private boolean captureReceiverRegistered = false;

    private MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler handler;
    private CardRecognizer cardRecognizer;
    private long lastOcrAtMs = 0;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private boolean frameCaptured = false;
    private boolean captureActive = false;
    private int capturedFrames = 0;
    private final Runnable captureFrameTask = () -> readCapturedFrame();

    // CARD_PICKER_SECTION
    private final PokerEngine.Card[] heroCards = new PokerEngine.Card[2];
    private final PokerEngine.Card[] boardCards = new PokerEngine.Card[5];
    private final Button[] heroCardButtons = new Button[2];
    private final Button[] boardCardButtons = new Button[5];
    private TextView cardResult;
    private boolean overlayPermissionPending = false;


    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handler = new Handler(Looper.getMainLooper());
        cardRecognizer = new CardRecognizer();
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


        // CARD_PICKER_SECTION: выбор карманных и общих карт
        addDivider(root);

        TextView cardsTitle = new TextView(this);
        cardsTitle.setText("КАРТЫ И ЭКВИТИ");
        cardsTitle.setTextColor(SILVER);
        cardsTitle.setTextSize(13);
        cardsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(cardsTitle);

        TextView cardsInfo = new TextView(this);
        cardsInfo.setText(
                "Выбери две свои карты, затем флоп, тёрн и ривер. " +
                "Эквити рассчитывается против случайной руки соперника.");
        cardsInfo.setTextColor(MUTED);
        cardsInfo.setTextSize(12);
        cardsInfo.setPadding(0, dp(8), 0, dp(12));
        root.addView(cardsInfo);

        TextView heroTitle = new TextView(this);
        heroTitle.setText("ТВОИ КАРТЫ");
        heroTitle.setTextColor(SILVER);
        heroTitle.setTextSize(12);
        root.addView(heroTitle);

        LinearLayout heroRow = new LinearLayout(this);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(heroRow);

        for (int i = 0; i < 2; i++) {
            final int index = i;
            Button b = makeButton("Выбрать");
            heroCardButtons[i] = b;
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.setMargins(0, dp(6), dp(6), dp(12));
            heroRow.addView(b, lp);
            b.setOnClickListener(v -> showCardPicker(true, index));
        }

        TextView boardTitle = new TextView(this);
        boardTitle.setText("ОБЩИЕ КАРТЫ");
        boardTitle.setTextColor(SILVER);
        boardTitle.setTextSize(12);
        root.addView(boardTitle);

        LinearLayout boardRow1 = new LinearLayout(this);
        boardRow1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(boardRow1);

        for (int i = 0; i < 3; i++) {
            final int index = i;
            Button b = makeButton("--");
            boardCardButtons[i] = b;
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(0, dp(6), dp(4), dp(6));
            boardRow1.addView(b, lp);
            b.setOnClickListener(v -> showCardPicker(false, index));
        }

        LinearLayout boardRow2 = new LinearLayout(this);
        boardRow2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(boardRow2);

        for (int i = 3; i < 5; i++) {
            final int index = i;
            Button b = makeButton("--");
            boardCardButtons[i] = b;
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(0, dp(4), dp(4), dp(8));
            boardRow2.addView(b, lp);
            b.setOnClickListener(v -> showCardPicker(false, index));
        }

        Button evaluateCards = makeButton("РАССЧИТАТЬ КОМБИНАЦИЮ И ЭКВИТИ");
        LinearLayout.LayoutParams evaluateParams =
                new LinearLayout.LayoutParams(-1, dp(54));
        evaluateParams.setMargins(0, dp(12), 0, dp(12));
        root.addView(evaluateCards, evaluateParams);

        LinearLayout cardPanel = new LinearLayout(this);
        cardPanel.setOrientation(LinearLayout.VERTICAL);
        cardPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        cardPanel.setBackgroundColor(PANEL);
        root.addView(cardPanel);

        cardResult = new TextView(this);
        cardResult.setTextColor(WHITE);
        cardResult.setTextSize(15);
        cardResult.setLineSpacing(dp(5), 1f);
        cardPanel.addView(cardResult);

        evaluateCards.setOnClickListener(v -> calculateCards());
        refreshCardButtons();
        cardResult.setText(
                "Выбери две карманные карты. " +
                "Для расчёта на флопе укажи три общие карты.");

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

        Button overlay = makeButton("ЗАПУСТИТЬ ПЛАВАЮЩЕЕ ОКНО");
        LinearLayout.LayoutParams overlayParams =
                new LinearLayout.LayoutParams(-1, dp(54));
        overlayParams.setMargins(0, dp(10), 0, dp(8));
        root.addView(overlay, overlayParams);

        TextView privacy = new TextView(this);
        privacy.setText("Захват запускается только после твоего подтверждения.");
        privacy.setTextColor(MUTED);
        privacy.setTextSize(11);
        privacy.setPadding(0, dp(10), 0, 0);
        root.addView(privacy);

        calculate.setOnClickListener(v -> calculate());
        capture.setOnClickListener(v -> requestScreenCapture());
        overlay.setOnClickListener(v -> requestOverlayPermissionOrStart());

        setContentView(scroll);
        calculate();
    }


    // CARD_PICKER_SECTION: ручной выбор карт

    private void requestOverlayPermissionOrStart() {
        if (android.provider.Settings.canDrawOverlays(this)) {
            overlayPermissionPending = false;
            startService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "Плавающее окно запущено", Toast.LENGTH_SHORT).show();
            return;
        }

        overlayPermissionPending = true;
        Intent settingsIntent = new Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName())
        );

        try {
            startActivity(settingsIntent);
        } catch (Exception e) {
            overlayPermissionPending = false;
            Toast.makeText(
                    this,
                    "Не удалось открыть настройки разрешения",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (overlayPermissionPending) {
            overlayPermissionPending = false;

            if (android.provider.Settings.canDrawOverlays(this)) {
                startService(new Intent(this, OverlayService.class));
                Toast.makeText(
                        this,
                        "Плавающее окно запущено",
                        Toast.LENGTH_SHORT
                ).show();
            } else {
                Toast.makeText(
                        this,
                        "Разрешение не предоставлено",
                        Toast.LENGTH_SHORT
                ).show();
            }
        }
    }

    private String cardLabel(PokerEngine.Card card) {
        if (card == null) return "--";

        String rank = card.toString().substring(0, 1);
        char suit = card.toString().charAt(1);

        String symbol;
        switch (suit) {
            case 's': symbol = "\u2660"; break;
            case 'h': symbol = "\u2665"; break;
            case 'd': symbol = "\u2666"; break;
            case 'c': symbol = "\u2663"; break;
            default: symbol = String.valueOf(suit);
        }

        return rank + symbol;
    }

    private void refreshCardButtons() {
        for (int i = 0; i < heroCardButtons.length; i++) {
            if (heroCardButtons[i] != null) {
                heroCardButtons[i].setText(
                        heroCards[i] == null
                                ? "Выбрать"
                                : cardLabel(heroCards[i]));
            }
        }

        for (int i = 0; i < boardCardButtons.length; i++) {
            if (boardCardButtons[i] != null) {
                boardCardButtons[i].setText(cardLabel(boardCards[i]));
            }
        }
    }

    private boolean cardAlreadyUsed(
            String code, boolean isHero, int targetIndex) {

        for (int i = 0; i < heroCards.length; i++) {
            if (isHero && i == targetIndex) continue;

            if (heroCards[i] != null &&
                    heroCards[i].toString().equals(code)) {
                return true;
            }
        }

        for (int i = 0; i < boardCards.length; i++) {
            if (!isHero && i == targetIndex) continue;

            if (boardCards[i] != null &&
                    boardCards[i].toString().equals(code)) {
                return true;
            }
        }

        return false;
    }

    private void showCardPicker(boolean isHero, int index) {
        List<String> options = new ArrayList<>();
        options.add("Очистить карту");

        String ranks = "23456789TJQKA";
        String suits = "shdc";

        for (int r = 0; r < ranks.length(); r++) {
            for (int su = 0; su < suits.length(); su++) {
                String code = "" + ranks.charAt(r) + suits.charAt(su);

                if (!cardAlreadyUsed(code, isHero, index)) {
                    options.add(code);
                }
            }
        }

        String[] labels = new String[options.size()];
        labels[0] = options.get(0);

        for (int i = 1; i < options.size(); i++) {
            labels[i] = cardLabel(PokerEngine.Card.parse(options.get(i)));
        }

        new AlertDialog.Builder(this)
                .setTitle("Выбери карту")
                .setItems(labels, (dialog, which) -> {
                    PokerEngine.Card selected = which == 0
                            ? null
                            : PokerEngine.Card.parse(options.get(which));

                    if (isHero) {
                        heroCards[index] = selected;
                    } else {
                        boardCards[index] = selected;
                    }

                    refreshCardButtons();
                    calculateCards();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }


    private void publishOverlayState() {
        StringBuilder hero = new StringBuilder();
        for (PokerEngine.Card card : heroCards) {
            if (hero.length() > 0) hero.append("   ");
            hero.append(card == null ? "--" : cardLabel(card));
        }

        StringBuilder board = new StringBuilder();
        for (PokerEngine.Card card : boardCards) {
            if (card == null) continue;
            if (board.length() > 0) board.append("   ");
            board.append(cardLabel(card));
        }

        String details = cardResult == null
                ? "Ожидание расчёта"
                : cardResult.getText().toString();

        String status = "Результаты ручного выбора карт";

        if (heroCards[0] == null || heroCards[1] == null) {
            status = "Выбери две свои карты";
        } else if (details.startsWith("Ошибка")
                || details.startsWith("Для борда")
                || details.startsWith("Заполняй")) {
            status = "Нужна проверка данных";
        } else if (details.contains("Эквити против случайной руки")) {
            status = "Эквити рассчитано против одной случайной руки";
        }

        getSharedPreferences("poker_hud", MODE_PRIVATE)
                .edit()
                .putString("hero", hero.toString())
                .putString("board", board.length() == 0
                        ? "Префлоп / карты не выбраны"
                        : board.toString())
                .putString("result", details)
                .putString("status", status)
                .apply();
    }

    private void calculateCards() {
        try {
            if (heroCards[0] == null || heroCards[1] == null) {
                cardResult.setText("Сначала выбери две свои карты.");
                publishOverlayState();
                return;
            }

            List<PokerEngine.Card> hero = new ArrayList<>();
            hero.add(heroCards[0]);
            hero.add(heroCards[1]);

            List<PokerEngine.Card> board = new ArrayList<>();
            boolean foundEmpty = false;

            for (PokerEngine.Card card : boardCards) {
                if (card == null) {
                    foundEmpty = true;
                } else {
                    if (foundEmpty) {
                        cardResult.setText(
                                "Заполняй общие карты по порядку: " +
                                "сначала флоп, затем тёрн и ривер.");
                        return;
                    }
                    board.add(card);
                }
            }

            if (board.size() == 1 || board.size() == 2) {
                cardResult.setText(
                        "Для борда выбери либо 0 карт, либо 3, 4 или 5.");
                return;
            }

            String handText;

            if (board.isEmpty()) {
                handText = "Комбинация: пока не определена (префлоп)";
            } else {
                List<PokerEngine.Card> all = new ArrayList<>(hero);
                all.addAll(board);
                handText = "Комбинация: " +
                        PokerEngine.describeHand(all);
            }

            PokerEngine.Result equity =
                    PokerEngine.calculateEquity(hero, board, 3000);

            cardResult.setText(String.format(
                    Locale.getDefault(),
                    "%s\n\nЭквити против случайной руки: %.1f%%" +
                    "\nПобеды: %d" +
                    "\nНичьи: %d" +
                    "\nПоражения: %d" +
                    "\nСимуляций: %d",
                    handText,
                    equity.equity,
                    equity.wins,
                    equity.ties,
                    equity.losses,
                    equity.simulations
            ));
            publishOverlayState();

        } catch (Exception e) {
            cardResult.setText("Ошибка расчёта: " + e.getMessage());
            publishOverlayState();
        }
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

        pendingCaptureResultCode = resultCode;
        pendingCaptureData = data;

        registerCaptureServiceReceiver();

        try {
            Intent serviceIntent = new Intent(this, ScreenCaptureService.class);

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            unregisterCaptureServiceReceiver();
            pendingCaptureData = null;
            Toast.makeText(
                    this,
                    "Не удалось запустить службу захвата: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void registerCaptureServiceReceiver() {
        unregisterCaptureServiceReceiver();

        captureServiceReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ScreenCaptureService.ACTION_READY.equals(intent.getAction())) {
                    return;
                }

                unregisterCaptureServiceReceiver();

                String serviceError = intent.getStringExtra("capture_service_error");
                if (serviceError != null) {
                    pendingCaptureData = null;
                    stopCaptureService();
                    Toast.makeText(
                            MainActivity.this,
                            "Ошибка службы захвата: " + serviceError,
                            Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                if (pendingCaptureData == null) {
                    stopCaptureService();
                    return;
                }

                startCaptureAfterServiceReady(
                        pendingCaptureResultCode,
                        pendingCaptureData
                );

                pendingCaptureData = null;
            }
        };

        android.content.IntentFilter filter =
                new android.content.IntentFilter(ScreenCaptureService.ACTION_READY);

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    captureServiceReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(captureServiceReceiver, filter);
        }

        captureReceiverRegistered = true;
    }

    private void unregisterCaptureServiceReceiver() {
        if (captureReceiverRegistered && captureServiceReceiver != null) {
            try {
                unregisterReceiver(captureServiceReceiver);
            } catch (Exception ignored) {
            }
        }

        captureReceiverRegistered = false;
        captureServiceReceiver = null;
    }

    private void startCaptureAfterServiceReady(int resultCode, Intent data) {
        try {
            projection = projectionManager.getMediaProjection(resultCode, data);

            if (projection == null) {
                stopCaptureService();
                Toast.makeText(
                        this,
                        "Не удалось начать захват",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            imageReader = ImageReader.newInstance(
                    screenWidth,
                    screenHeight,
                    PixelFormat.RGBA_8888,
                    2
            );

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
                    handler
            );

            captureActive = true;
            capturedFrames = 0;
            handler.removeCallbacks(captureFrameTask);
            handler.postDelayed(captureFrameTask, 500);

        } catch (Exception e) {
            releaseCaptureResources();

            Toast.makeText(
                    this,
                    "Ошибка захвата: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void readCapturedFrame() {
        if (!captureActive || imageReader == null) {
            return;
        }

        Image image = null;
        Bitmap padded = null;
        Bitmap frame = null;

        try {
            image = imageReader.acquireLatestImage();

            if (image == null) {
                return;
            }

            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) {
                return;
            }

            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();

            if (pixelStride <= 0 || rowStride <= 0 || screenWidth <= 0
                    || screenHeight <= 0) {
                return;
            }

            int rowPadding = rowStride - pixelStride * screenWidth;
            int paddedWidth = screenWidth + Math.max(0, rowPadding / pixelStride);

            padded = Bitmap.createBitmap(
                    paddedWidth,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
            );

            buffer.rewind();
            padded.copyPixelsFromBuffer(buffer);

            frame = Bitmap.createBitmap(
                    padded,
                    0,
                    0,
                    screenWidth,
                    screenHeight
            );

            capturedFrames++;
            runOcrOnFrame(frame);

        } catch (Exception e) {
            android.util.Log.e(
                    "PokerAssistant",
                    "Ошибка чтения кадра",
                    e
            );
        } finally {
            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }

            if (padded != null && !padded.isRecycled()) {
                padded.recycle();
            }

            if (image != null) {
                image.close();
            }

            if (captureActive && imageReader != null && handler != null) {
                handler.removeCallbacks(captureFrameTask);
                handler.postDelayed(captureFrameTask, 300);
            }
        }
    }

    private void runOcrOnFrame(Bitmap frame) {
        if (!captureActive || cardRecognizer == null
                || frame == null || frame.isRecycled()) {
            return;
        }

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOcrAtMs < 1200 || cardRecognizer.isProcessing()) {
            return;
        }

        lastOcrAtMs = now;
        Bitmap ocrBitmap = null;

        try {
            ocrBitmap = frame.copy(Bitmap.Config.ARGB_8888, false);
            if (ocrBitmap == null) {
                return;
            }

            final Bitmap bitmapForOcr = ocrBitmap;

            cardRecognizer.recognize(bitmapForOcr, new CardRecognizer.Callback() {
                @Override
                public void onSuccess(
                        java.util.List<CardRecognizer.RecognizedText> items,
                        String fullText) {
                    try {
                        android.util.Log.d(
                                "PokerAssistantOCR",
                                "Текст экрана: " + fullText
                        );

                        android.content.SharedPreferences prefs =
                                getSharedPreferences("poker_hud", MODE_PRIVATE);

                        String detected = fullText == null ? "" : fullText.trim();
                        String status = detected.isEmpty()
                                ? "OCR: текст не найден"
                                : "OCR: текст распознан";

                        prefs.edit()
                                .putString("ocr_text", detected)
                                .putString("ocr_status", status)
                                .apply();
                    } finally {
                        if (!bitmapForOcr.isRecycled()) {
                            bitmapForOcr.recycle();
                        }
                    }
                }

                @Override
                public void onFailure(Exception error) {
                    try {
                        android.util.Log.e(
                                "PokerAssistantOCR",
                                "Ошибка распознавания текста",
                                error
                        );
                    } finally {
                        if (!bitmapForOcr.isRecycled()) {
                            bitmapForOcr.recycle();
                        }
                    }
                }
            });
        } catch (Exception e) {
            if (ocrBitmap != null && !ocrBitmap.isRecycled()) {
                ocrBitmap.recycle();
            }
            android.util.Log.e("PokerAssistantOCR", "Не удалось запустить OCR", e);
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
        captureActive = false;
        if (handler != null) handler.removeCallbacks(captureFrameTask);
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            MediaProjection oldProjection = projection;
            projection = null;
            try {
                oldProjection.stop();
            } catch (Exception ignored) {
            }
        }
        stopCaptureService();
    }

    private void stopCaptureService() {
        try {
            stopService(new Intent(this, ScreenCaptureService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        unregisterCaptureServiceReceiver();
        pendingCaptureData = null;
        releaseCaptureResources();

        if (cardRecognizer != null) {
            cardRecognizer.close();
            cardRecognizer = null;
        }

        super.onDestroy();
    }
}
