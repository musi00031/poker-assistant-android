package com.pokerassistant.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes the two hero cards from the fixed ClubGG screen locations.
 *
 * The ONNX model is only accepted when it returns a reasonably confident result.
 * Otherwise ML Kit runs against the whole card and several corner crops. Rank is
 * read independently from suit; suit-color inference is conservative and is used
 * only when a single clear color dominates the card corner.
 */
public final class FixedPlayerCardRecognizer {
    private static final String TAG = "PokerCardOCR";
    private FixedPlayerCardRecognizer() {}

    public static final int REFERENCE_WIDTH = 1220;
    public static final int REFERENCE_HEIGHT = 2712;

    private static final Rect LEFT_CARD = new Rect(50, 1945, 170, 2140);
    private static final Rect RIGHT_CARD = new Rect(160, 1945, 280, 2140);

    private static final Pattern RANK_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9])(10|[2-9TJQKA])(?![A-Z0-9])");
    private static final Pattern SUIT_SYMBOL_PATTERN =
            Pattern.compile("[♠♤♣♧♥♡♦♢]");
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9])(10|[2-9TJQKA])\\s*([♠♤♣♧♥♡♦♢SHDC])(?![A-Z0-9])");

    private static final ExecutorService MODEL_EXECUTOR =
            Executors.newSingleThreadExecutor();

    public static final class CardRegion {
        public final String position;
        public final Rect bounds;
        public final Bitmap image;

        private CardRegion(String position, Rect bounds, Bitmap image) {
            this.position = position;
            this.bounds = new Rect(bounds);
            this.image = image;
        }
    }

    public static final class CardTextResult {
        public final String position;
        public final Rect bounds;
        /** Normalized card code (for example As), or raw OCR text if unresolved. */
        public final String text;
        public final String error;
        public final String rawText;

        private CardTextResult(String position, Rect bounds, String text,
                               String error, String rawText) {
            this.position = position;
            this.bounds = new Rect(bounds);
            this.text = text;
            this.error = error;
            this.rawText = rawText == null ? "" : rawText;
        }

        @Override
        public String toString() {
            String label = "PLAYER_LEFT".equals(position) ? "Левая карта" : "Правая карта";
            if (error != null) return label + ": ошибка чтения изображения";
            List<PokerEngine.Card> parsed = PlayingCardTextParser.parse(text);
            if (parsed.size() == 1) {
                PokerEngine.Card c = parsed.get(0);
                String ranks = "23456789TJQKA";
                String rank = String.valueOf(ranks.charAt(c.rank - 2));
                if ("T".equals(rank)) rank = "10";
                String[] suits = {"♠", "♥", "♦", "♣"};
                return label + ": " + rank + suits[c.suit]
                        + " (гибрид OCR/цвет; " + (rawText.isEmpty() ? "без OCR-текста" : rawText) + ")";
            }
            if (text == null || text.trim().isEmpty())
                return label + ": не найдена — проверь область карты";
            return label + ": не удалось уверенно определить карту (OCR: " + text + ")";
        }
    }

    public interface Callback {
        void onComplete(List<CardTextResult> results);
    }

    public static List<CardRegion> extract(Bitmap frame) {
        List<CardRegion> result = new ArrayList<>();
        if (frame == null || frame.isRecycled()
                || frame.getWidth() <= 0 || frame.getHeight() <= 0) return result;

        Bitmap workingFrame = frame;
        boolean rotatedFrame = false;
        if (frame.getWidth() > frame.getHeight()) {
            android.graphics.Matrix rotation = new android.graphics.Matrix();
            rotation.postRotate(90f);
            workingFrame = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(),
                    frame.getHeight(), rotation, true);
            rotatedFrame = workingFrame != frame;
        }

        addRegion(result, workingFrame, "PLAYER_LEFT", LEFT_CARD);
        addRegion(result, workingFrame, "PLAYER_RIGHT", RIGHT_CARD);
        if (rotatedFrame && !workingFrame.isRecycled()) workingFrame.recycle();
        return result;
    }

    private static void addRegion(List<CardRegion> result, Bitmap frame,
                                  String position, Rect referenceBounds) {
        float sx = frame.getWidth() / (float) REFERENCE_WIDTH;
        float sy = frame.getHeight() / (float) REFERENCE_HEIGHT;
        Rect bounds = new Rect(Math.round(referenceBounds.left * sx),
                Math.round(referenceBounds.top * sy),
                Math.round(referenceBounds.right * sx),
                Math.round(referenceBounds.bottom * sy));
        if (!bounds.intersect(0, 0, frame.getWidth(), frame.getHeight())
                || bounds.width() <= 0 || bounds.height() <= 0) return;

        Bitmap crop = Bitmap.createBitmap(frame, bounds.left, bounds.top,
                bounds.width(), bounds.height());
        Bitmap enlarged = Bitmap.createScaledBitmap(crop,
                Math.max(1, crop.getWidth() * 5),
                Math.max(1, crop.getHeight() * 5), true);
        if (enlarged != crop) crop.recycle();
        result.add(new CardRegion(position, bounds, enlarged));
    }

    public static void recognize(Context context, Bitmap frame, Callback callback) {
        List<CardRegion> regions = extract(frame);
        if (regions.size() != 2) {
            releaseRegions(regions);
            callback.onComplete(new ArrayList<>());
            return;
        }

        MODEL_EXECUTOR.execute(() -> {
            boolean modelAccepted = false;
            try {
                OnnxCardDetector detector = OnnxCardDetector.get(context);
                List<CardTextResult> modelResults = new ArrayList<>();
                for (CardRegion region : regions) {
                    String code = detector.detect(region.image);
                    if (code == null) {
                        modelResults.clear();
                        break;
                    }
                    modelResults.add(new CardTextResult(region.position, region.bounds,
                            code, null, "ONNX"));
                }
                if (modelResults.size() == 2
                        && !modelResults.get(0).text.equals(modelResults.get(1).text)) {
                    modelAccepted = true;
                    releaseRegions(regions);
                    Log.i(TAG, "ONNX confidently recognized both fixed hero-card regions");
                    callback.onComplete(modelResults);
                }
            } catch (Exception e) {
                Log.w(TAG, "ONNX unavailable; using multi-view OCR and suit-color fallback", e);
            }
            if (!modelAccepted) recognizeWithOcr(regions, callback);
        });
    }

    private static void recognizeWithOcr(List<CardRegion> regions, Callback callback) {
        TextRecognizer recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);
        List<CardTextResult> results = new ArrayList<>();
        processRegion(regions, 0, results, recognizer, callback);
    }

    private static void processRegion(List<CardRegion> regions, int regionIndex,
                                      List<CardTextResult> results,
                                      TextRecognizer recognizer, Callback callback) {
        if (regionIndex >= regions.size()) {
            releaseRegions(regions);
            recognizer.close();
            callback.onComplete(results);
            return;
        }

        CardRegion region = regions.get(regionIndex);
        List<Bitmap> views = new ArrayList<>();
        List<Bitmap> ownedViews = new ArrayList<>();
        views.add(region.image); // Original card image stays alive for suit-color analysis.
        try {
            // The rank/suit index is normally concentrated in the top-left corner.
            int w = region.image.getWidth();
            int h = region.image.getHeight();
            Bitmap corner = Bitmap.createBitmap(region.image, 0, 0,
                    Math.max(1, Math.min(w, Math.round(w * 0.62f))),
                    Math.max(1, Math.min(h, Math.round(h * 0.60f))));
            views.add(corner);
            ownedViews.add(corner);

            // A tighter view often prevents OCR from being distracted by the table.
            Bitmap tight = Bitmap.createBitmap(region.image, 0, 0,
                    Math.max(1, Math.min(w, Math.round(w * 0.42f))),
                    Math.max(1, Math.min(h, Math.round(h * 0.43f))));
            views.add(tight);
            ownedViews.add(tight);
        } catch (Exception e) {
            Log.w(TAG, region.position + ": unable to prepare corner crops", e);
        }

        List<String> readings = new ArrayList<>();
        processView(region, views, ownedViews, 0, readings, recognizer, () -> {
            CardTextResult result = makeHybridResult(region, readings);
            results.add(result);
            Log.i(TAG, region.position + " OCR views=" + readings
                    + "; result=" + result.text + "; detector=" + OnnxCardDetector.getDiagnostic());
            processRegion(regions, regionIndex + 1, results, recognizer, callback);
        });
    }

    private static void processView(CardRegion region, List<Bitmap> views,
                                    List<Bitmap> ownedViews, int viewIndex,
                                    List<String> readings, TextRecognizer recognizer,
                                    Runnable finished) {
        if (viewIndex >= views.size()) {
            for (Bitmap bitmap : ownedViews) recycle(bitmap);
            finished.run();
            return;
        }

        Bitmap view = views.get(viewIndex);
        try {
            recognizer.process(InputImage.fromBitmap(view, 0))
                    .addOnSuccessListener(result -> {
                        String value = result.getText() == null ? "" : result.getText().trim();
                        readings.add(value);
                        Log.d(TAG, region.position + " view " + viewIndex + ": "
                                + (value.isEmpty() ? "<empty>" : value.replace("\n", " | ")));
                        processView(region, views, ownedViews, viewIndex + 1,
                                readings, recognizer, finished);
                    })
                    .addOnFailureListener(error -> {
                        readings.add("");
                        Log.w(TAG, region.position + " OCR view " + viewIndex + " failed", error);
                        processView(region, views, ownedViews, viewIndex + 1,
                                readings, recognizer, finished);
                    });
        } catch (Exception e) {
            readings.add("");
            Log.w(TAG, region.position + " OCR view " + viewIndex + " exception", e);
            processView(region, views, ownedViews, viewIndex + 1,
                    readings, recognizer, finished);
        }
    }

    private static CardTextResult makeHybridResult(CardRegion region, List<String> readings) {
        List<String> nonEmpty = new ArrayList<>();
        for (String s : readings) if (s != null && !s.trim().isEmpty()) nonEmpty.add(s.trim());
        String raw = String.join(" / ", nonEmpty);

        String exactCode = findUnambiguousExactCode(nonEmpty);
        String rank = findUnambiguousRank(nonEmpty);
        Character suit = findSuitSymbol(nonEmpty);
        String suitSource = "OCR";

        if (exactCode != null) {
            if (rank == null || exactCode.charAt(0) == rank.charAt(0)) {
                return new CardTextResult(region.position, region.bounds,
                        exactCode, null, raw + "; карта из OCR");
            }
        }

        if (suit == null) {
            SuitColor color = classifySuitColor(region.image);
            if (color != null) {
                suit = color.code;
                suitSource = "цвет:" + color.name + "/" + color.score;
            }
        }

        if (rank != null && suit != null) {
            String code = rank + suit;
            try {
                PokerEngine.Card.parse(code);
                return new CardTextResult(region.position, region.bounds,
                        code, null, raw + "; rank=" + rank + "; " + suitSource);
            } catch (IllegalArgumentException ignored) {}
        }

        // Keep the raw OCR for diagnostics, but never invent a missing rank or suit.
        String diagnostic = raw;
        if (rank != null) diagnostic += "; ранг=" + rank;
        if (suit != null) diagnostic += "; масть=" + suit;
        return new CardTextResult(region.position, region.bounds,
                diagnostic, null, raw);
    }

    private static String findUnambiguousExactCode(List<String> readings) {
        Map<String, Integer> counts = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (String text : readings) {
            List<PokerEngine.Card> cards = PlayingCardTextParser.parse(text);
            Set<String> inThisReading = new HashSet<>();
            for (PokerEngine.Card card : cards) inThisReading.add(card.toString());
            if (inThisReading.size() > 1) conflicts.addAll(inThisReading);
            for (String code : inThisReading) counts.put(code, counts.getOrDefault(code, 0) + 1);
        }
        String winner = null;
        int best = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                winner = entry.getKey();
                best = entry.getValue();
                tie = false;
            } else if (entry.getValue() == best) {
                tie = true;
            }
        }
        if (winner == null || tie || best == 0) return null;
        for (String code : counts.keySet()) {
            if (!code.equals(winner) && counts.get(code) >= best) return null;
        }
        return winner;
    }

    private static String findUnambiguousRank(List<String> readings) {
        Map<String, Integer> counts = new HashMap<>();
        Set<String> all = new HashSet<>();
        for (String text : readings) {
            Matcher matcher = RANK_PATTERN.matcher(text == null ? "" : text.toUpperCase());
            Set<String> inReading = new HashSet<>();
            while (matcher.find()) {
                String value = matcher.group(1).toUpperCase();
                String rank = "10".equals(value) ? "T" : value;
                inReading.add(rank);
            }
            // Only count the view if it yields one distinct rank.
            if (inReading.size() == 1) {
                String rank = inReading.iterator().next();
                counts.put(rank, counts.getOrDefault(rank, 0) + 1);
                all.add(rank);
            } else {
                all.addAll(inReading);
            }
        }
        if (all.size() != 1) return null;
        return all.iterator().next();
    }

    private static Character findSuitSymbol(List<String> readings) {
        Set<Character> symbols = new HashSet<>();
        for (String text : readings) {
            if (text == null) continue;
            Matcher matcher = SUIT_SYMBOL_PATTERN.matcher(text);
            while (matcher.find()) {
                char c = matcher.group().charAt(0);
                switch (c) {
                    case '♠': case '♤': symbols.add('s'); break;
                    case '♥': case '♡': symbols.add('h'); break;
                    case '♦': case '♢': symbols.add('d'); break;
                    case '♣': case '♧': symbols.add('c'); break;
                    default: break;
                }
            }
        }
        return symbols.size() == 1 ? symbols.iterator().next() : null;
    }

    private static final class SuitColor {
        final char code;
        final String name;
        final int score;
        SuitColor(char code, String name, int score) {
            this.code = code; this.name = name; this.score = score;
        }
    }

    /**
     * ClubGG's four-color card faces use red=hearts, blue=diamonds,
     * green=clubs, black=spades. If a chromatic suit color is not clearly
     * dominant, this method refuses to infer it rather than returning a guess.
     */
    private static SuitColor classifySuitColor(Bitmap card) {
        if (card == null || card.isRecycled()) return null;
        int left = Math.max(0, Math.round(card.getWidth() * 0.04f));
        int right = Math.max(left + 1, Math.min(card.getWidth(), Math.round(card.getWidth() * 0.62f)));
        int top = Math.max(0, Math.round(card.getHeight() * 0.02f));
        int bottom = Math.max(top + 1, Math.min(card.getHeight(), Math.round(card.getHeight() * 0.60f)));
        int stepX = Math.max(1, (right - left) / 180);
        int stepY = Math.max(1, (bottom - top) / 220);
        int red = 0, blue = 0, green = 0, black = 0;

        for (int y = top; y < bottom; y += stepY) {
            for (int x = left; x < right; x += stepX) {
                int pixel = card.getPixel(x, y);
                int r = android.graphics.Color.red(pixel);
                int g = android.graphics.Color.green(pixel);
                int b = android.graphics.Color.blue(pixel);
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (r > 105 && r > g * 1.35f && r > b * 1.25f) red++;
                else if (b > 90 && b > r * 1.25f && b > g * 1.12f) blue++;
                else if (g > 90 && g > r * 1.15f && g > b * 1.10f) green++;
                else if (max < 75 && max - min < 45) black++;
            }
        }

        int chromaticMax = Math.max(red, Math.max(blue, green));
        int chromaticSecond = Math.max(Math.min(red, blue),
                Math.max(Math.min(red, green), Math.min(blue, green)));
        if (chromaticMax >= 8 && chromaticMax >= chromaticSecond * 1.30f) {
            if (red == chromaticMax) return new SuitColor('h', "красный", red);
            if (blue == chromaticMax) return new SuitColor('d', "синий", blue);
            return new SuitColor('c', "зелёный", green);
        }

        // Black is ambiguous in a two-color deck. Only use it when there is
        // no detectable red/blue/green and a substantial dark glyph cluster.
        if (chromaticMax < 5 && black >= 14) {
            return new SuitColor('s', "чёрный", black);
        }
        return null;
    }

    private static void releaseRegions(List<CardRegion> regions) {
        for (CardRegion region : regions) recycle(region.image);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
