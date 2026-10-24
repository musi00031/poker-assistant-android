package com.pokerassistant.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Recognizes community cards inside the fixed table-center ROI.
 * A strong ONNX detection is preferred; if the visual deck differs from the
 * model's training set, ML Kit text + the color of the card index is used.
 */
final class BoardCardRecognizer {
    private static final String TAG = "PokerBoardModel";
    private static final int REF_W = 1220;
    private static final int REF_H = 2712;
    // Measured from the supplied ClubGG screenshot: only the 3–5 community-card
    // row is included, excluding pot text, chips, player panels and table logo.
    // Normalized to the 1220x2712 reference canvas.
    private static final Rect BOARD_ROI = new Rect(228, 1248, 704, 1498);
    private static final float ROW_Y_TOLERANCE = 0.10f;
    private static final int MIN_BOARD_CARDS = 3;
    private static final int MAX_BOARD_CARDS = 5;
    private static final float MIN_MODEL_CONFIDENCE = 0.28f;

    private static final java.util.concurrent.ExecutorService EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    interface Callback {
        void onComplete(List<PokerEngine.Card> cards, String diagnostic);
    }

    private static final class CardCandidate {
        final PokerEngine.Card card;
        final Rect bounds;
        final float confidence;

        CardCandidate(PokerEngine.Card card, Rect bounds, float confidence) {
            this.card = card;
            this.bounds = new Rect(bounds);
            this.confidence = confidence;
        }
    }

    private BoardCardRecognizer() {}

    static void recognize(Context context, Bitmap frame, Callback callback) {
        Bitmap crop = extractBoardCrop(context, frame);
        if (crop == null) {
            callback.onComplete(Collections.emptyList(), "Область общих карт вне кадра");
            return;
        }

        // V2: disable the old YOLO/ONNX detector. On actual ClubGG frames it
        // produced virtually zero class scores; analyze the crop directly instead.
        Log.i(TAG, "V2 active: legacy ONNX disabled; running multi-view OCR/color pipeline");
        EXECUTOR.execute(() -> recognizeByOcrAndColor(
                crop, callback, "V2: старый ONNX отключён"));
    }

    private static List<PokerEngine.Card> parseDetections(
            List<OnnxCardDetector.Detection> detections) {
        List<PokerEngine.Card> cards = new ArrayList<>();
        for (OnnxCardDetector.Detection detection : detections) {
            try {
                cards.add(PokerEngine.Card.parse(detection.code));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed labels; invalid cards never enter calculations.
            }
        }
        return cards;
    }

    private static void recognizeByOcrAndColor(
            Bitmap crop, Callback callback, String modelDiagnostic) {
        // Run two independent OCR views of the same fixed board ROI. Small card
        // indices can disappear at 2x, while a high-contrast 3x view often helps.
        // Both OCR passes map their boxes back to the original crop before suit-color
        // sampling; all candidates are merged and spatially deduplicated.
        final List<CardCandidate> merged = new ArrayList<>();
        runOcrView(crop, 2.0f, false, merged, () ->
                runOcrView(crop, 3.0f, true, merged, () -> {
                    try {
                        List<PokerEngine.Card> row = chooseOcrRow(merged, crop.getHeight());
                        if (row.size() >= MIN_BOARD_CARDS
                                && row.size() <= MAX_BOARD_CARDS
                                && !hasDuplicates(row)) {
                            callback.onComplete(row, "OCR multi-view (2× + 3× contrast): "
                                    + format(row) + " · " + modelDiagnostic
                                    + "; кандидаты=" + summarize(merged));
                        } else {
                            callback.onComplete(Collections.emptyList(),
                                    "Борд пока не подтверждён: multi-view OCR не дал "
                                            + "3–5 уникальных карт. " + modelDiagnostic
                                            + "; OCR-кандидаты=" + summarize(merged));
                        }
                    } finally {
                        recycle(crop);
                    }
                }));
    }

    private interface OcrPassDone {
        void run();
    }

    private static void runOcrView(
            Bitmap crop, float scale, boolean highContrast,
            List<CardCandidate> merged, OcrPassDone done) {
        Bitmap scaledImage = null;
        TextRecognizer recognizer = null;
        try {
            scaledImage = Bitmap.createScaledBitmap(
                    crop,
                    Math.max(1, Math.round(crop.getWidth() * scale)),
                    Math.max(1, Math.round(crop.getHeight() * scale)),
                    true);
            if (highContrast) {
                int width = scaledImage.getWidth();
                int height = scaledImage.getHeight();
                int[] pixels = new int[width * height];
                scaledImage.getPixels(pixels, 0, width, 0, 0, width, height);
                for (int i = 0; i < pixels.length; i++) {
                    int color = pixels[i];
                    int r = (color >> 16) & 255;
                    int g = (color >> 8) & 255;
                    int b = color & 255;
                    int gray = (r * 299 + g * 587 + b * 114) / 1000;
                    // Gentle contrast stretch; avoid hard thresholding thin rank strokes.
                    int enhanced = Math.max(0, Math.min(255,
                            Math.round((gray - 128) * 1.45f + 128)));
                    pixels[i] = android.graphics.Color.rgb(enhanced, enhanced, enhanced);
                }
                scaledImage.setPixels(pixels, 0, width, 0, 0, width, height);
            }
            final Bitmap ocrImage = scaledImage;
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            final TextRecognizer activeRecognizer = recognizer;
            activeRecognizer.process(InputImage.fromBitmap(ocrImage, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            List<CardCandidate> found = extractOcrCandidates(crop, text, scale);
                            for (CardCandidate candidate : found) {
                                addCandidate(merged, candidate.card, candidate.bounds,
                                        candidate.confidence);
                            }
                            Log.d(TAG, "OCR view " + scale + "x contrast=" + highContrast
                                    + " candidates=" + found.size());
                        } catch (Exception e) {
                            Log.w(TAG, "OCR view failed at " + scale + "x", e);
                        } finally {
                            activeRecognizer.close();
                            if (ocrImage != crop) recycle(ocrImage);
                            done.run();
                        }
                    })
                    .addOnFailureListener(error -> {
                        Log.w(TAG, "ML Kit OCR view failed at " + scale + "x", error);
                        activeRecognizer.close();
                        if (ocrImage != crop) recycle(ocrImage);
                        done.run();
                    });
        } catch (Exception e) {
            Log.w(TAG, "Could not start OCR view at " + scale + "x", e);
            if (scaledImage != null && scaledImage != crop) recycle(scaledImage);
            if (recognizer != null) recognizer.close();
            done.run();
        }
    }

    private static List<CardCandidate> extractOcrCandidates(
            Bitmap crop, Text text, float scale) {
        List<CardCandidate> candidates = new ArrayList<>();
        if (text == null || text.getTextBlocks() == null) return candidates;

        List<CardRecognizer.RecognizedText> allItems = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            Rect ocrBounds = block.getBoundingBox();
            if (ocrBounds == null || block.getText() == null) continue;
            // OCR runs against an enlarged bitmap; map text boxes back to the original crop
            // before colour sampling and row geometry checks.
            Rect bounds = new Rect(
                    Math.round(ocrBounds.left / scale),
                    Math.round(ocrBounds.top / scale),
                    Math.round(ocrBounds.right / scale),
                    Math.round(ocrBounds.bottom / scale));
            allItems.add(new CardRecognizer.RecognizedText(
                    block.getText(), bounds));
            if (fullText.length() > 0) fullText.append('\n');
            fullText.append(block.getText());
        }

        for (CardRecognizer.RecognizedText item : allItems) {
            Rect bounds = item.bounds;
            if (!cardLikeTextBounds(bounds, crop.getWidth(), crop.getHeight())) continue;

            List<PokerEngine.Card> exact = PlayingCardTextParser.parse(item.text);
            if (exact.size() == 1) {
                PokerEngine.Card exactCard = exact.get(0);
                // Validate OCR's Latin suit letter against the pixels around the
                // tiny rank/suit index. A strong red/blue/green signal can correct
                // C/S/H/D OCR confusions; black alone cannot separate spades/clubs.
                String visualSuit = CardImageRecognizer.detectSuitColor(
                        crop, expand(bounds, 1.35f, crop.getWidth(), crop.getHeight()));
                char ocrSuit = exactCard.toString().charAt(1);
                if (visualSuit != null && !visualSuit.isEmpty()
                        && visualSuit.charAt(0) != 's'
                        && visualSuit.charAt(0) != ocrSuit
                        && ("h".equals(visualSuit) || "d".equals(visualSuit)
                            || "c".equals(visualSuit))) {
                    try {
                        exactCard = PokerEngine.Card.parse(
                                exactCard.toString().substring(0, 1) + visualSuit);
                        Log.i(TAG, "Board suit corrected by pixel color: "
                                + item.text + " -> " + exactCard);
                    } catch (IllegalArgumentException ignored) {}
                }
                addCandidate(candidates, exactCard, bounds, 0.98f);
                continue;
            }

            List<CardImageRecognizer.Result> rankCandidates =
                    CardImageRecognizer.recognize(crop,
                            Collections.singletonList(item), item.text);
            for (CardImageRecognizer.Result result : rankCandidates) {
                if (result == null || result.rank == null || result.bounds == null) continue;
                String suit = result.suit == null ? "" : result.suit;
                float confidence = result.confidence;

                if (suit.isEmpty()) {
                    Rect colorBounds = expand(result.bounds, 1.20f,
                            crop.getWidth(), crop.getHeight());
                    suit = CardImageRecognizer.detectSuitColor(crop, colorBounds);
                    if (suit != null && !suit.isEmpty()) confidence = Math.min(0.68f, confidence + 0.18f);
                }
                if (suit == null || suit.isEmpty()) continue;

                try {
                    PokerEngine.Card card = PokerEngine.Card.parse(result.rank + suit);
                    addCandidate(candidates, card, result.bounds, confidence);
                } catch (IllegalArgumentException ignored) {
                    // OCR/color did not yield a valid card code.
                }
            }
        }

        return candidates;
    }

    private static boolean cardLikeTextBounds(Rect b, int width, int height) {
        if (b == null || b.isEmpty()) return false;
        if (b.centerX() < width * 0.015f || b.centerX() > width * 0.985f) return false;
        if (b.centerY() < height * 0.05f || b.centerY() > height * 0.95f) return false;
        if (b.width() < Math.max(7, width * 0.006f)
                || b.height() < Math.max(12, height * 0.012f)) return false;
        if (b.width() > width * 0.16f || b.height() > height * 0.20f) return false;
        return true;
    }

    private static Rect expand(Rect source, float factor, int maxWidth, int maxHeight) {
        int cx = source.centerX(), cy = source.centerY();
        int w = Math.max(1, Math.round(source.width() * factor));
        int h = Math.max(1, Math.round(source.height() * factor));
        Rect expanded = new Rect(cx - w / 2, cy - h / 2,
                cx + (w + 1) / 2, cy + (h + 1) / 2);
        expanded.intersect(0, 0, maxWidth, maxHeight);
        return expanded;
    }

    private static void addCandidate(List<CardCandidate> candidates,
                                     PokerEngine.Card card, Rect bounds, float confidence) {
        if (card == null || bounds == null || bounds.isEmpty()) return;
        for (int i = 0; i < candidates.size(); i++) {
            CardCandidate existing = candidates.get(i);
            // OCR can return both a text block and a crop-level candidate for one glyph.
            if (Math.abs(existing.bounds.centerX() - bounds.centerX())
                    < Math.max(16f, bounds.width() * 0.75f)
                    && Math.abs(existing.bounds.centerY() - bounds.centerY())
                    < Math.max(22f, bounds.height() * 0.75f)) {
                if (confidence > existing.confidence) {
                    candidates.set(i, new CardCandidate(card, bounds, confidence));
                }
                return;
            }
        }
        candidates.add(new CardCandidate(card, bounds, confidence));
    }

    private static List<PokerEngine.Card> chooseOcrRow(
            List<CardCandidate> candidates, int cropHeight) {
        if (candidates == null || candidates.size() < MIN_BOARD_CARDS) {
            return Collections.emptyList();
        }

        List<CardCandidate> byY = new ArrayList<>(candidates);
        byY.sort(Comparator.comparingDouble(c -> c.bounds.centerY()));
        float tolerance = Math.max(30f, cropHeight * ROW_Y_TOLERANCE);
        List<CardCandidate> best = Collections.emptyList();
        float bestScore = -1f;

        for (CardCandidate seed : byY) {
            List<CardCandidate> cluster = new ArrayList<>();
            float confidenceSum = 0f;
            for (CardCandidate candidate : byY) {
                if (Math.abs(candidate.bounds.centerY() - seed.bounds.centerY()) <= tolerance) {
                    cluster.add(candidate);
                    confidenceSum += candidate.confidence;
                }
            }
            float score = cluster.size() * 10f + confidenceSum;
            if (cluster.size() > best.size()
                    || (cluster.size() == best.size() && score > bestScore)) {
                best = cluster;
                bestScore = score;
            }
        }

        if (best.size() < MIN_BOARD_CARDS) return Collections.emptyList();
        List<CardCandidate> selected = new ArrayList<>(best);

        // Do not silently truncate a noisy OCR row to five cards: that can turn
        // table labels/buttons into a plausible but incorrect board.
        if (selected.size() > MAX_BOARD_CARDS) return Collections.emptyList();
        selected.sort(Comparator.comparingDouble(c -> c.bounds.centerX()));

        if (!isPlausibleOcrRow(selected, cropHeight)) return Collections.emptyList();

        List<PokerEngine.Card> cards = new ArrayList<>();
        for (CardCandidate candidate : selected) {
            if (cards.contains(candidate.card)) return Collections.emptyList();
            cards.add(candidate.card);
        }
        return cards;
    }

    private static List<OnnxCardDetector.Detection> chooseBoardRow(
            List<OnnxCardDetector.Detection> detections, int cropHeight) {
        if (detections == null || detections.size() < MIN_BOARD_CARDS) {
            return Collections.emptyList();
        }

        List<OnnxCardDetector.Detection> byY = new ArrayList<>(detections);
        byY.sort(Comparator.comparingDouble(d -> d.bounds.centerY()));
        float tolerance = Math.max(28f, cropHeight * ROW_Y_TOLERANCE);
        List<OnnxCardDetector.Detection> best = Collections.emptyList();
        float bestScore = -1f;

        for (OnnxCardDetector.Detection seed : byY) {
            List<OnnxCardDetector.Detection> cluster = new ArrayList<>();
            float confidenceSum = 0f;
            for (OnnxCardDetector.Detection candidate : byY) {
                if (Math.abs(candidate.bounds.centerY() - seed.bounds.centerY()) <= tolerance) {
                    cluster.add(candidate);
                    confidenceSum += candidate.confidence;
                }
            }
            float score = cluster.size() * 10f + confidenceSum;
            if (cluster.size() > best.size()
                    || (cluster.size() == best.size() && score > bestScore)) {
                best = cluster;
                bestScore = score;
            }
        }
        if (best.size() < MIN_BOARD_CARDS) return Collections.emptyList();

        // A row with more than five detections is ambiguous; never choose an
        // arbitrary confidence-ranked subset because that can mix in UI graphics.
        if (best.size() > MAX_BOARD_CARDS) return Collections.emptyList();

        List<OnnxCardDetector.Detection> selected = new ArrayList<>(best);
        selected.sort(Comparator.comparingDouble(d -> d.bounds.centerX()));
        if (!isPlausibleDetectionRow(selected, cropHeight)) return Collections.emptyList();
        return selected;
    }

    /**
     * Rejects scattered OCR glyphs that happen to resemble cards. Board cards
     * should form one horizontal sequence with comparable glyph heights and
     * increasing x-centres; uncertain rows fall back to "not detected".
     */
    private static boolean isPlausibleOcrRow(List<CardCandidate> row, int cropHeight) {
        if (row.size() < MIN_BOARD_CARDS || row.size() > MAX_BOARD_CARDS) return false;
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = 0;
        int minWidth = Integer.MAX_VALUE;
        int maxWidth = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = 0;
        for (CardCandidate c : row) {
            minHeight = Math.min(minHeight, c.bounds.height());
            maxHeight = Math.max(maxHeight, c.bounds.height());
            minWidth = Math.min(minWidth, c.bounds.width());
            maxWidth = Math.max(maxWidth, c.bounds.width());
            minY = Math.min(minY, c.bounds.centerY());
            maxY = Math.max(maxY, c.bounds.centerY());
        }
        if (maxY - minY > Math.max(18, Math.round(cropHeight * 0.055f))) return false;
        if (minHeight <= 0 || maxHeight > minHeight * 2.8f) return false;
        if (minWidth <= 0 || maxWidth > minWidth * 4.5f) return false;
        for (int i = 1; i < row.size(); i++) {
            int gap = row.get(i).bounds.centerX() - row.get(i - 1).bounds.centerX();
            if (gap <= 0) return false;
            int glyphWidth = Math.max(row.get(i).bounds.width(), row.get(i - 1).bounds.width());
            if (gap < Math.max(8, glyphWidth / 2)) return false;
        }
        return true;
    }

    private static boolean isPlausibleDetectionRow(
            List<OnnxCardDetector.Detection> row, int cropHeight) {
        if (row.size() < MIN_BOARD_CARDS || row.size() > MAX_BOARD_CARDS) return false;
        float minHeight = Float.MAX_VALUE;
        float maxHeight = 0f;
        float minY = Float.MAX_VALUE;
        float maxY = 0f;
        for (OnnxCardDetector.Detection d : row) {
            minHeight = Math.min(minHeight, d.bounds.height());
            maxHeight = Math.max(maxHeight, d.bounds.height());
            minY = Math.min(minY, d.bounds.centerY());
            maxY = Math.max(maxY, d.bounds.centerY());
        }
        if (maxY - minY > Math.max(18f, cropHeight * 0.055f)) return false;
        if (minHeight <= 0f || maxHeight > minHeight * 1.7f) return false;
        for (int i = 1; i < row.size(); i++) {
            float gap = row.get(i).bounds.centerX() - row.get(i - 1).bounds.centerX();
            float minGap = Math.min(row.get(i).bounds.width(), row.get(i - 1).bounds.width());
            if (gap <= 0f || gap < minGap * 0.45f) return false;
        }
        return true;
    }

    private static Bitmap extractBoardCrop(Context context, Bitmap frame) {
        if (frame == null || frame.isRecycled()
                || frame.getWidth() < 2 || frame.getHeight() < 2) return null;

        Bitmap working = frame;
        boolean rotated = false;
        if (frame.getWidth() > frame.getHeight()) {
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(90f);
            working = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(),
                    frame.getHeight(), matrix, true);
            rotated = working != frame;
        }

        Rect selected = CalibrationRegions.get(context, "board", BOARD_ROI, REF_W, REF_H);
        float sx = working.getWidth() / (float) REF_W;
        float sy = working.getHeight() / (float) REF_H;
        Rect scaled = new Rect(Math.round(selected.left * sx),
                Math.round(selected.top * sy), Math.round(selected.right * sx),
                Math.round(selected.bottom * sy));
        if (!scaled.intersect(0, 0, working.getWidth(), working.getHeight())
                || scaled.width() < 20 || scaled.height() < 20) {
            if (rotated) recycle(working);
            return null;
        }

        Bitmap crop = Bitmap.createBitmap(working, scaled.left, scaled.top,
                scaled.width(), scaled.height());
        if (rotated) recycle(working);
        return crop;
    }

    private static boolean hasDuplicates(List<PokerEngine.Card> cards) {
        for (int i = 0; i < cards.size(); i++) {
            for (int j = i + 1; j < cards.size(); j++) {
                if (cards.get(i).equals(cards.get(j))) return true;
            }
        }
        return false;
    }

    private static String format(List<PokerEngine.Card> cards) {
        StringBuilder summary = new StringBuilder();
        for (PokerEngine.Card card : cards) {
            if (summary.length() > 0) summary.append(' ');
            summary.append(card.toString());
        }
        return summary.toString();
    }

    private static String summarize(List<CardCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "нет";
        StringBuilder text = new StringBuilder();
        for (CardCandidate candidate : candidates) {
            if (text.length() > 0) text.append(' ');
            text.append(candidate.card).append('@')
                    .append(String.format(java.util.Locale.US, "%.2f", candidate.confidence))
                    .append('#').append(candidate.bounds.centerX()).append(',')
                    .append(candidate.bounds.centerY());
        }
        return text.toString();
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
