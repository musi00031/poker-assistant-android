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

    private final int BG = Color.rgb(8, 9, 12);
    private final int PANEL = Color.rgb(21, 22, 27);
    private final int SILVER = Color.rgb(205, 211, 224);
    private final int ACCENT = Color.rgb(196, 42, 57);
    private final int MUTED = Color.rgb(145, 145, 155);
    private final int WHITE = Color.WHITE;
    private static final int SCREEN_CAPTURE_REQUEST = 7001;

    private EditText potInput;
    private EditText betInput;
    private EditText callInput;
    private EditText equityInput;
    private TextView result;
    private TextView captureStatusView;
    private TextView captureFramesView;
    private ImageView capturePreviewView;
    private long lastPreviewAtMs = 0;
    private TextView ocrResultView;
    private Button captureButton;

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
    private TextView cardSourceStatus;
    private boolean autoCardMode = true;
    private boolean fixedCardOcrInProgress = false;
    private String latestPlayerDiagnostic = "ТВОИ КАРТЫ\nОжидание распознавания";
    private String latestBoardDiagnostic = "ОБЩИЕ КАРТЫ\nОжидание распознавания";
    private volatile boolean boardCardOcrInProgress = false;
    private String pendingBoardKey = null;
    private int pendingBoardCount = 0;
    private static final int REQUIRED_BOARD_CONFIRMATIONS = 2;

    // Подтверждение пары карт по последовательным результатам OCR.
    private String pendingCardPair = null;
    private int pendingCardPairCount = 0;
    private static final int REQUIRED_CARD_CONFIRMATIONS = 3;


    private void refreshDiagnosticsText() {
        if (ocrResultView == null) return;
        String model = OnnxCardDetector.getDiagnostic();
        if (model == null || model.trim().isEmpty()) model = "Модель ещё не дала результат";
        if (model.length() > 190) model = "…" + model.substring(model.length() - 190);
        ocrResultView.setText(latestPlayerDiagnostic + "\n\n"
                + latestBoardDiagnostic + "\n\nONNX: " + model);
    }

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

        // Захват должен использовать полный физический размер дисплея,
        // а не размеры окна приложения (на Android 15/16 они могут отличаться).
        android.util.DisplayMetrics captureMetrics =
                new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(captureMetrics);
        screenWidth = captureMetrics.widthPixels;
        screenHeight = captureMetrics.heightPixels;
        screenDensity = captureMetrics.densityDpi;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(root);

        TextView brand = new TextView(this);
        brand.setText("POKER ASSISTANT  /  PRO");
        brand.setTextColor(SILVER);
        brand.setTextSize(12);
        brand.setLetterSpacing(0.18f);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(brand);

        TextView title = new TextView(this);
        title.setText("Покерный\nассистент");
        title.setTextColor(WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(18), 0, dp(6));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("CLUBGG · АВТОРАСПОЗНАВАНИЕ · LIVE АНАЛИЗ");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(14);
        root.addView(subtitle);

        addDivider(root);


        TextView cardsTitle = new TextView(this);
        cardsTitle.setText("КАРТЫ И ЭКВИТИ");
        cardsTitle.setTextColor(SILVER);
        cardsTitle.setTextSize(13);
        cardsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(cardsTitle);

        TextView cardsInfo = new TextView(this);
        cardsInfo.setText(
                "Карты игрока и борд определяются автоматически по кадрам ClubGG. " +
                "Комбинация, эквити и подсказки пересчитываются без ручного ввода.");
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
            b.setClickable(false);
            b.setFocusable(false);
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
            b.setClickable(false);
            b.setFocusable(false);
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
            b.setClickable(false);
            b.setFocusable(false);
        }

        LinearLayout cardPanel = new LinearLayout(this);
        cardPanel.setOrientation(LinearLayout.VERTICAL);
        cardPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        cardPanel.setBackground(roundedPanel(PANEL, Color.rgb(56, 58, 68), 18));
        root.addView(cardPanel);

        cardSourceStatus = new TextView(this);
        cardSourceStatus.setTextColor(SILVER);
        cardSourceStatus.setTextSize(12);
        cardSourceStatus.setPadding(0, 0, 0, dp(8));
        cardSourceStatus.setText("Автораспознавание карт включено");
        cardPanel.addView(cardSourceStatus);

        cardResult = new TextView(this);
        cardResult.setTextColor(WHITE);
        cardResult.setTextSize(15);
        cardResult.setLineSpacing(dp(5), 1f);
        cardPanel.addView(cardResult);

        refreshCardButtons();
        cardResult.setText(
                "Ожидание автоматического распознавания.\nПодсказка: не закрывай карты оверлеем и дождись подтверждения нескольких кадров.");

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

        captureStatusView = new TextView(this);
        captureStatusView.setText("●  ЗАХВАТ ЭКРАНА НЕ АКТИВЕН");
        captureStatusView.setTextColor(MUTED);
        captureStatusView.setTextSize(14);
        captureStatusView.setPadding(0, dp(12), 0, dp(8));
        root.addView(captureStatusView);

        captureFramesView = new TextView(this);
        captureFramesView.setText("Обработано кадров: 0");
        captureFramesView.setTextColor(MUTED);
        captureFramesView.setTextSize(12);
        root.addView(captureFramesView);

        TextView previewTitle = new TextView(this);
        previewTitle.setText("ПОСЛЕДНИЙ КАДР ЗАХВАТА");
        previewTitle.setTextColor(SILVER);
        previewTitle.setTextSize(12);
        previewTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(previewTitle);

        capturePreviewView = new ImageView(this);
        capturePreviewView.setBackground(roundedPanel(PANEL, Color.rgb(56, 58, 68), 12));
        capturePreviewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        capturePreviewView.setAdjustViewBounds(true);
        capturePreviewView.setMaxHeight(dp(440));
        capturePreviewView.setMinimumHeight(dp(160));
        root.addView(capturePreviewView, new LinearLayout.LayoutParams(-1, dp(440)));

        TextView ocrTitle = new TextView(this);
        ocrTitle.setText("АНАЛИЗАТОР · ДИАГНОСТИКА И ПОДСКАЗКИ");
        ocrTitle.setTextColor(SILVER);
        ocrTitle.setTextSize(12);
        ocrTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ocrTitle.setPadding(0, dp(16), 0, dp(8));
        root.addView(ocrTitle);

        ocrResultView = new TextView(this);
        ocrResultView.setText("Ожидание запуска распознавания.");
        ocrResultView.setTextColor(WHITE);
        ocrResultView.setTextSize(13);
        ocrResultView.setMaxLines(8);
        ocrResultView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ocrResultView.setPadding(dp(12), dp(12), dp(12), dp(12));
        ocrResultView.setBackground(roundedPanel(PANEL, Color.rgb(56, 58, 68), 14));
        // Фиксированная высота не даёт длинным диагностическим сообщениям сдвигать интерфейс.
        root.addView(ocrResultView, new LinearLayout.LayoutParams(-1, dp(190)));

        Button overlayButton = makeButton("ПОКАЗАТЬ ПАНЕЛЬ ПОВЕРХ ИГРЫ");
        LinearLayout.LayoutParams overlayParams =
                new LinearLayout.LayoutParams(-1, dp(50));
        overlayParams.setMargins(0, dp(10), 0, dp(4));
        root.addView(overlayButton, overlayParams);
        overlayButton.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 23
                    && !android.provider.Settings.canDrawOverlays(this)) {
                Intent permissionIntent = new Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())
                );
                startActivity(permissionIntent);
                Toast.makeText(
                        this,
                        "Разреши отображение поверх других приложений, затем нажми кнопку ещё раз",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }
            Intent overlayIntent = new Intent(this, OverlayService.class);
            overlayIntent.setAction(OverlayService.ACTION_TOGGLE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(overlayIntent);
            } else {
                startService(overlayIntent);
            }
        });

        captureButton = makeButton("НАЧАТЬ ДЕМОНСТРАЦИЮ ЭКРАНА");
        root.addView(captureButton, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView privacy = new TextView(this);
        privacy.setText("Захват запускается только после твоего подтверждения.");
        privacy.setTextColor(MUTED);
        privacy.setTextSize(11);
        privacy.setPadding(0, dp(10), 0, 0);
        root.addView(privacy);

        captureButton.setOnClickListener(v -> {
            if (captureActive) {
                releaseCaptureResources();
                updateCaptureStatus("○ Демонстрация остановлена", MUTED);
                if (ocrResultView != null) {
                    ocrResultView.setText("Захват остановлен.");
                }
            } else {
                requestScreenCapture();
            }
        });


        setContentView(scroll);
    }


    // CARD_PICKER_SECTION: ручной выбор карт
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

    private void applyRecognizedPlayerCards(
            java.util.List<FixedPlayerCardRecognizer.CardTextResult> results) {

        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> applyRecognizedPlayerCards(results));
            return;
        }

        if (!autoCardMode || results == null || results.isEmpty()) {
            pendingCardPair = null;
            pendingCardPairCount = 0;
            android.util.Log.d(
                    "PokerCardOCR",
                    "Результаты отсутствуют или автораспознавание выключено"
            );
            return;
        }

        PokerEngine.Card left = null;
        PokerEngine.Card right = null;
        boolean invalidResult = false;

        for (FixedPlayerCardRecognizer.CardTextResult item : results) {
            if (item == null || item.text == null || item.error != null) {
                invalidResult = true;
                break;
            }

            java.util.List<PokerEngine.Card> parsed =
                    PlayingCardTextParser.parse(item.text);

            android.util.Log.d(
                    "PokerCardOCR",
                    item.position + " PARSE: OCR=[" + item.text
                            + "], cards="
                            + (parsed == null ? "null" : parsed.toString())
            );

            if (parsed == null || parsed.size() != 1) {
                invalidResult = true;
                break;
            }

            if ("PLAYER_LEFT".equals(item.position)) {
                left = parsed.get(0);
            } else if ("PLAYER_RIGHT".equals(item.position)) {
                right = parsed.get(0);
            }
        }

        if (invalidResult
                || left == null
                || right == null
                || left.toString().equals(right.toString())) {
            pendingCardPair = null;
            pendingCardPairCount = 0;

            String rejectionReason;
            if (invalidResult) {
                rejectionReason = "в одной из областей не удалось определить одну карту";
            } else if (left == null) {
                rejectionReason = "левая карта не найдена";
            } else if (right == null) {
                rejectionReason = "правая карта не найдена";
            } else {
                rejectionReason = "обе карты одинаковые: " + left;
            }

            android.util.Log.d(
                    "PokerCardOCR",
                    "Пара отклонена: " + rejectionReason
            );

            runOnUiThread(() -> {
                if (autoCardMode && cardSourceStatus != null) {
                    cardSourceStatus.setText(
                            "Пока не вижу обе карты. Проверь положение карт внизу стола."
                    );
                }
            });
            return;
        }

        final PokerEngine.Card detectedLeft = left;
        final PokerEngine.Card detectedRight = right;
        final String pairKey =
                detectedLeft.toString() + "|" + detectedRight.toString();

        if (pairKey.equals(pendingCardPair)) {
            pendingCardPairCount++;
        } else {
            pendingCardPair = pairKey;
            pendingCardPairCount = 1;
        }

        final int confirmations = pendingCardPairCount;

        android.util.Log.d(
                "PokerCardOCR",
                "Подтверждение пары: " + pairKey
                        + "; кадр " + confirmations
                        + "/" + REQUIRED_CARD_CONFIRMATIONS
        );

        if (confirmations < REQUIRED_CARD_CONFIRMATIONS) {
            runOnUiThread(() -> {
                if (autoCardMode && cardSourceStatus != null) {
                    cardSourceStatus.setText(
                            "Проверяю карты: "
                                    + confirmations + "/"
                                    + REQUIRED_CARD_CONFIRMATIONS
                    );
                }
            });
            return;
        }

        runOnUiThread(() -> {
            if (!autoCardMode) {
                return;
            }

            for (PokerEngine.Card board : boardCards) {
                if (board != null
                        && (board.toString().equals(detectedLeft.toString())
                        || board.toString().equals(detectedRight.toString()))) {
                    pendingCardPair = null;
                    pendingCardPairCount = 0;

                    if (cardSourceStatus != null) {
                        cardSourceStatus.setText(
                                "Конфликт с общей картой — проверь распознавание"
                        );
                    }
                    return;
                }
            }

            if (heroCards[0] != null
                    && heroCards[1] != null
                    && heroCards[0].toString().equals(detectedLeft.toString())
                    && heroCards[1].toString().equals(detectedRight.toString())) {
                return;
            }

            boolean heroPairChanged = heroCards[0] == null || heroCards[1] == null
                    || !heroCards[0].equals(detectedLeft)
                    || !heroCards[1].equals(detectedRight);
            if (heroPairChanged) {
                for (int i = 0; i < boardCards.length; i++) boardCards[i] = null;
                pendingBoardKey = null;
                pendingBoardCount = 0;
            }
            heroCards[0] = detectedLeft;
            heroCards[1] = detectedRight;
            publishOverlayState("Карты: " + cardLabel(detectedLeft) + " " + cardLabel(detectedRight));

            refreshCardButtons();

            if (cardSourceStatus != null) {
                cardSourceStatus.setText(
                        "Распознаны карты: "
                                + cardLabel(detectedLeft) + " и "
                                + cardLabel(detectedRight)
                );
            }

            calculateCards();
        });
    }

    private void applyRecognizedBoardCards(
            List<PokerEngine.Card> detected, String diagnostic) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> applyRecognizedBoardCards(detected, diagnostic));
            return;
        }
        if (!autoCardMode) return;
        if (detected == null || detected.size() < 3 || detected.size() > 5) {
            android.util.Log.d("PokerBoardModel", diagnostic == null
                    ? "Общие карты не подтверждены" : diagnostic);
            latestBoardDiagnostic = "ОБЩИЕ КАРТЫ\n"
                    + (diagnostic == null ? "Не удалось подтвердить 3–5 карт" : diagnostic);
            refreshDiagnosticsText();
            if (cardSourceStatus != null) cardSourceStatus.setText("Борд: карты не подтверждены");
            pendingBoardKey = null;
            pendingBoardCount = 0;
            return;
        }

        for (PokerEngine.Card card : detected) {
            if (card == null || (heroCards[0] != null && heroCards[0].equals(card))
                    || (heroCards[1] != null && heroCards[1].equals(card))) {
                pendingBoardKey = null;
                pendingBoardCount = 0;
                return;
            }
        }
        for (int i = 0; i < detected.size(); i++) {
            for (int j = i + 1; j < detected.size(); j++) {
                if (detected.get(i).equals(detected.get(j))) {
                    pendingBoardKey = null;
                    pendingBoardCount = 0;
                    return;
                }
            }
        }

        StringBuilder keyBuilder = new StringBuilder();
        for (PokerEngine.Card card : detected) keyBuilder.append(card.toString()).append('|');
        String key = keyBuilder.toString();
        if (key.equals(pendingBoardKey)) pendingBoardCount++;
        else {
            pendingBoardKey = key;
            pendingBoardCount = 1;
        }
        if (pendingBoardCount < REQUIRED_BOARD_CONFIRMATIONS) {
            if (cardSourceStatus != null) {
                cardSourceStatus.setText("Проверяю общие карты: " + pendingBoardCount
                        + "/" + REQUIRED_BOARD_CONFIRMATIONS);
            }
            return;
        }

        int existingCount = 0;
        for (PokerEngine.Card card : boardCards) if (card != null) existingCount++;
        if (detected.size() < existingCount) {
            // Never downgrade a turn/river to a partial flop when the detector misses a card.
            return;
        }

        boolean changed = detected.size() != existingCount;
        for (int i = 0; i < detected.size(); i++) {
            if (boardCards[i] == null || !boardCards[i].equals(detected.get(i))) changed = true;
        }
        if (!changed) return;

        for (int i = 0; i < boardCards.length; i++) {
            boardCards[i] = i < detected.size() ? detected.get(i) : null;
        }
        refreshCardButtons();
        if (cardSourceStatus != null) {
            StringBuilder status = new StringBuilder("Распознаны общие карты: ");
            for (PokerEngine.Card card : detected) status.append(cardLabel(card)).append(' ');
            cardSourceStatus.setText(status.toString().trim());
        }
        StringBuilder boardSummary = new StringBuilder("ОБЩИЕ КАРТЫ\n");
        for (PokerEngine.Card card : detected) boardSummary.append(cardLabel(card)).append(' ');
        latestBoardDiagnostic = boardSummary.toString().trim();
        refreshDiagnosticsText();
        calculateCards();
    }

    private void refreshCardButtons() {
        for (int i = 0; i < heroCardButtons.length; i++) {
            if (heroCardButtons[i] != null) {
                heroCardButtons[i].setText(
                        heroCards[i] == null
                                ? "Ожидание"
                                : cardLabel(heroCards[i]));
            }
        }

        for (int i = 0; i < boardCardButtons.length; i++) {
            if (boardCardButtons[i] != null) {
                boardCardButtons[i].setText(boardCards[i] == null ? "—" : cardLabel(boardCards[i]));
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



    private void publishOverlayState(String state) {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(OverlayService.ACTION_UPDATE);
        intent.putExtra(OverlayService.EXTRA_STATE, state);
        try {
            startService(intent);
        } catch (Exception error) {
            android.util.Log.w("PokerOverlay", "Не удалось обновить панель", error);
        }
    }

    private void calculateCards() {
        try {
            if (heroCards[0] == null || heroCards[1] == null) {
                String message = "Сначала выбери две свои карты.";
                cardResult.setText(message);
                publishOverlayState(message);
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
                        String message = "Заполняй общие карты по порядку: "
                                + "сначала флоп, затем тёрн и ривер.";
                        cardResult.setText(message);
                        publishOverlayState(message);
                        return;
                    }
                    board.add(card);
                }
            }

            if (board.size() == 1 || board.size() == 2) {
                String message = "Для борда выбери либо 0 карт, либо 3, 4 или 5.";
                cardResult.setText(message);
                publishOverlayState(message);
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
            LiveHandAnalyzer.Report liveReport =
                    LiveHandAnalyzer.analyze(hero, board, equity.equity);

            String overlaySummary = String.format(
                    Locale.getDefault(),
                    "%s\nЭквити: %.1f%%\n%s\n%s",
                    liveReport.street, equity.equity,
                    liveReport.hand, liveReport.advice);
            publishOverlayState(overlaySummary);

            cardResult.setText(String.format(
                    Locale.getDefault(),
                    "%s\n\n%s\n%s\n\nЭквити против случайной руки: %.1f%%" +
                    "\nПобеды: %d · Ничьи: %d · Поражения: %d" +
                    "\nРасчётов: %d\n\nВажно: это ориентир против случайной руки, не точный диапазон соперника.",
                    liveReport.street,
                    liveReport.hand,
                    liveReport.draws,
                    equity.equity,
                    equity.wins,
                    equity.ties,
                    equity.losses,
                    equity.simulations
            ));
} catch (Exception e) {
            String message = "Ошибка расчёта: " + e.getMessage();
            cardResult.setText(message);
            publishOverlayState(message);
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

    private android.graphics.drawable.GradientDrawable roundedPanel(
            int fill, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(SILVER);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setLetterSpacing(0.025f);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(roundedPanel(
                Color.rgb(31, 32, 39), Color.rgb(82, 84, 96), 13));
        if (text.toUpperCase(Locale.ROOT).contains("РАССЧИТАТЬ")
                || text.toUpperCase(Locale.ROOT).contains("ПОКАЗАТЬ ПАНЕЛЬ")) {
            button.setTextColor(Color.WHITE);
            button.setBackground(roundedPanel(ACCENT, Color.rgb(235, 85, 96), 13));
        }
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
        updateCaptureStatus("◷ Ожидание разрешения Android…", SILVER);
        if (ocrResultView != null) {
            ocrResultView.setText("Подтверди системный запрос на демонстрацию экрана.");
        }
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
            updateCaptureStatus("○ Демонстрация не активна", MUTED);
            if (ocrResultView != null) {
                ocrResultView.setText("Разрешение на захват не предоставлено.");
            }
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
            updateCaptureStatus("● Демонстрация экрана активна", Color.rgb(90, 220, 130));
            if (captureFramesView != null) {
                captureFramesView.setText("Обработано кадров: 0");
            }
            if (ocrResultView != null) {
                ocrResultView.setText("Захват запущен. Ожидание распознавания текста…");
            }
            if (captureButton != null) {
                captureButton.setText("ОСТАНОВИТЬ ДЕМОНСТРАЦИЮ");
            }
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
            if (captureFramesView != null && capturedFrames % 3 == 0) {
                captureFramesView.setText("Обработано кадров: " + capturedFrames
                        + " · кадр " + screenWidth + "×" + screenHeight);
            }
            updateCapturePreview(frame);
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

    private void updateCapturePreview(Bitmap frame) {
        if (capturePreviewView == null || frame == null || frame.isRecycled()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastPreviewAtMs < 2500) return;
        lastPreviewAtMs = now;
        try {
            int maxWidth = Math.min(480, Math.max(240, frame.getWidth()));
            float scale = maxWidth / (float) frame.getWidth();
            int targetWidth = Math.max(1, Math.round(frame.getWidth() * scale));
            int targetHeight = Math.max(1, Math.round(frame.getHeight() * scale));
            Bitmap preview = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true);
            runOnUiThread(() -> {
                if (capturePreviewView != null) capturePreviewView.setImageBitmap(preview);
            });
            android.util.Log.i("PokerCapturePreview", "Кадр захвата: "
                    + frame.getWidth() + "x" + frame.getHeight()
                    + ", preview=" + targetWidth + "x" + targetHeight);
        } catch (Exception error) {
            android.util.Log.e("PokerCapturePreview", "Не удалось показать кадр захвата", error);
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

                        String detected = fullText == null ? "" : fullText.trim();

                        java.util.List<CardImageRecognizer.Result> imageResults =
                                CardImageRecognizer.recognize(
                                        bitmapForOcr,
                                        items,
                                        detected
                                );

                        java.util.List<PokerEngine.Card> cardCandidates =
                                PlayingCardTextParser.parse(detected);

                        StringBuilder imageSummary = new StringBuilder();
                        for (CardImageRecognizer.Result result : imageResults) {
                            if (imageSummary.length() > 0) {
                                imageSummary.append("  ");
                            }
                            imageSummary.append(result.toString());

            // Диагностический этап: цвет OCR-области.
            // Цвет не считается подтверждением масти, поскольку OCR bounds
            // могут включать фон, ранг и соседние символы.
            if (result.bounds != null
                    && !result.bounds.isEmpty()
                    && bitmapForOcr != null
                    && !bitmapForOcr.isRecycled()) {
                String colorDiagnostic =
                        CardImageRecognizer.detectSuitColor(
                                bitmapForOcr,
                                result.bounds
                        );
                imageSummary.append(" | цвет области=")
                        .append(colorDiagnostic)
                        .append(" (не подтверждено)");
            }

            if (result.bounds != null) {
                imageSummary.append(" | координаты=")
                        .append(result.bounds.flattenToString());
            }
                        }

                        String displayText = "ПРОВЕРКА КАРТ\n"
                                + (detected.isEmpty()
                                    ? "Изображение экрана получено. Читаемый текст не найден.\n"
                                    : "Экран получен. Проверяю две твои карты…\n")
                                + "Если карты не появятся ниже, проверь, что они полностью видны на столе.";

                        String status = detected.isEmpty()
                                ? "Экран получен, карты ещё не найдены"
                                : "Экран получен, проверяю карты";

                        runOnUiThread(() -> {
                            updateCaptureStatus(
                                    detected.isEmpty()
                                            ? "● Захват активен · текст не найден"
                                            : "● Захват активен · текст распознан",
                                    detected.isEmpty() ? SILVER : Color.rgb(90, 220, 130)
                            );
                        });

                        // Общие карты ищутся отдельной моделью в центральной области стола.
                        // ROI копируется синхронно до освобождения исходного кадра.
                        if (!boardCardOcrInProgress) {
                            boardCardOcrInProgress = true;
                            try {
                                BoardCardRecognizer.recognize(
                                        MainActivity.this, bitmapForOcr,
                                        (board, diagnostic) -> {
                                            boardCardOcrInProgress = false;
                                            applyRecognizedBoardCards(board, diagnostic);
                                        });
                            } catch (Exception error) {
                                boardCardOcrInProgress = false;
                                android.util.Log.e("PokerBoardModel",
                                        "Не удалось запустить распознавание общих карт", error);
                            }
                        }

                        // Отдельное последовательное OCR фиксированных карт игрока.
                        // Изображения областей создаются до освобождения исходного кадра.
                        if (!fixedCardOcrInProgress) {
                            fixedCardOcrInProgress = true;
                            try {
                                FixedPlayerCardRecognizer.recognize(
                                    MainActivity.this,
                                    bitmapForOcr,
                                    fixedResults -> {
                                        fixedCardOcrInProgress = false;
                                        applyRecognizedPlayerCards(fixedResults);

                                    StringBuilder fixedSummary = new StringBuilder();

                                    for (FixedPlayerCardRecognizer.CardTextResult item
                                            : fixedResults) {
                                        if (fixedSummary.length() > 0) {
                                            fixedSummary.append("\n");
                                        }
                                        fixedSummary.append(item.toString());
                                    }

                                    StringBuilder playerSummary = new StringBuilder("ТВОИ КАРТЫ\n");
                                    if (fixedSummary.length() == 0) {
                                        playerSummary.append("Не удалось получить изображение карт");
                                    } else {
                                        playerSummary.append(fixedSummary);
                                    }
                                    for (FixedPlayerCardRecognizer.CardTextResult item : fixedResults) {
                                        playerSummary.append("\nROI ")
                                                .append(item.position).append(": ")
                                                .append(item.bounds.flattenToString());
                                    }
                                    String playerText = playerSummary.toString();
                                    runOnUiThread(() -> {
                                        latestPlayerDiagnostic = playerText;
                                        refreshDiagnosticsText();
                                    });
                                    }
                                );
                            } catch (Exception error) {
                                fixedCardOcrInProgress = false;
                                android.util.Log.e(
                                        "PokerCardOCR",
                                        "Не удалось запустить распознавание карт",
                                        error
                                );
                            }
                        }

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
                        runOnUiThread(() -> {
                            if (ocrResultView != null) {
                                ocrResultView.setText(
                                        "Ошибка OCR: " + error.getMessage()
                                );
                            }
                        });
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



    private void updateCaptureStatus(String message, int color) {
        if (captureStatusView != null) {
            captureStatusView.setText(message);
            captureStatusView.setTextColor(color);
        }
        if (captureButton != null) {
            captureButton.setText(
                    captureActive
                            ? "ОСТАНОВИТЬ ДЕМОНСТРАЦИЮ"
                            : "НАЧАТЬ ДЕМОНСТРАЦИЮ ЭКРАНА"
            );
        }
    }

    private void releaseCaptureResources() {
        captureActive = false;
        updateCaptureStatus("○ Демонстрация экрана не активна", MUTED);
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
