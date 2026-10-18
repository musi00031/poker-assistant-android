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
    private static final Rect BOARD_ROI = new Rect(32, 704, 1136, 1824);
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
        Bitmap crop = extractBoardCrop(frame);
        if (crop == null) {
            callback.onComplete(Collections.emptyList(), "Область общих карт вне кадра");
            return;
        }

        EXECUTOR.execute(() -> {
            boolean cropTransferredToOcr = false;
            try {
                OnnxCardDetector detector = OnnxCardDetector.get(context);
                List<OnnxCardDetector.Detection> detections = detector.detectAll(crop);
                List<OnnxCardDetector.Detection> row = chooseBoardRow(
                        detections, crop.getHeight());
                List<PokerEngine.Card> cards = parseDetections(row);
                boolean strongModelRow = cards.size() >= MIN_BOARD_CARDS
                        && cards.size() <= MAX_BOARD_CARDS
                        && !hasDuplicates(cards)
                        && row.stream().allMatch(d -> d.confidence >= MIN_MODEL_CONFIDENCE);

                if (strongModelRow) {
                    String summary = "ONNX подтверждён: " + format(cards);
                    Log.i(TAG, summary);
                    callback.onComplete(cards, summary);
                } else {
                    String modelDiagnostic = "ONNX недостаточно уверен (карт="
                            + cards.size() + ", пики: " + OnnxCardDetector.getDiagnostic() + ")";
                    cropTransferredToOcr = true;
                    recognizeByOcrAndColor(crop, callback, modelDiagnostic);
                }
            } catch (Exception e) {
                String failure = "ONNX: " + e.getClass().getSimpleName()
                        + "; пробую OCR/цвет";
                Log.e(TAG, "Ошибка ONNX; включаю OCR fallback", e);
                cropTransferredToOcr = true;
                recognizeByOcrAndColor(crop, callback, failure);
            } finally {
                if (!cropTransferredToOcr && !crop.isRecycled()) crop.recycle();
            }
        });
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
        final float ocrScale = 1.5f;
        Bitmap scaledImage = null;
        TextRecognizer createdRecognizer = null;
        try {
            scaledImage = Bitmap.createScaledBitmap(
                    crop,
                    Math.max(1, Math.round(crop.getWidth() * ocrScale)),
                    Math.max(1, Math.round(crop.getHeight() * ocrScale)),
                    true);
            final Bitmap ocrImage = scaledImage;
            createdRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            final TextRecognizer recognizer = createdRecognizer;
            recognizer.process(InputImage.fromBitmap(ocrImage, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            List<CardCandidate> candidates =
                                    extractOcrCandidates(crop, text, ocrScale);
                            List<PokerEngine.Card> row = chooseOcrRow(candidates, crop.getHeight());
                            if (row.size() >= MIN_BOARD_CARDS
                                    && row.size() <= MAX_BOARD_CARDS
                                    && !hasDuplicates(row)) {
                                callback.onComplete(row, "OCR+цвет (увеличение "
                                        + ocrScale + "×): " + format(row)
                                        + " · " + modelDiagnostic);
                            } else {
                                callback.onComplete(Collections.emptyList(),
                                        "Борд пока не подтверждён: ONNX и OCR/цвет не дали "
                                                + "3–5 уникальных карт. " + modelDiagnostic
                                                + "; OCR-кандидаты=" + summarize(candidates));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Ошибка OCR fallback общих карт", e);
                            callback.onComplete(Collections.emptyList(),
                                    "Ошибка OCR fallback: " + e.getClass().getSimpleName()
                                            + "; " + modelDiagnostic);
                        } finally {
                            recognizer.close();
                            if (ocrImage != crop) recycle(ocrImage);
                            recycle(crop);
                        }
                    })
                    .addOnFailureListener(error -> {
                        Log.w(TAG, "ML Kit не прочитал область общих карт", error);
                        recognizer.close();
                        if (ocrImage != crop) recycle(ocrImage);
                        recycle(crop);
                        callback.onComplete(Collections.emptyList(),
                                "OCR/цвет не сработал: " + error.getClass().getSimpleName()
                                        + "; " + modelDiagnostic);
                    });
        } catch (Exception e) {
            Log.e(TAG, "Не удалось запустить ML Kit для общих карт", e);
            if (scaledImage != null && scaledImage != crop) recycle(scaledImage);
            if (createdRecognizer != null) createdRecognizer.close();
            recycle(crop);
            callback.onComplete(Collections.emptyList(),
                    "Не удалось запустить OCR: " + e.getClass().getSimpleName()
                            + "; " + modelDiagnostic);
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
                addCandidate(candidates, exact.get(0), bounds, 0.98f);
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

        // If extra OCR words fall on the same row, retain the strongest five card-like items.
        if (selected.size() > MAX_BOARD_CARDS) {
            selected.sort((a, b) -> Float.compare(b.confidence, a.confidence));
            selected = new ArrayList<>(selected.subList(0, MAX_BOARD_CARDS));
        }
        selected.sort(Comparator.comparingDouble(c -> c.bounds.centerX()));

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

        List<OnnxCardDetector.Detection> selected = new ArrayList<>(best);
        if (selected.size() > MAX_BOARD_CARDS) {
            selected.sort((a, b) -> Float.compare(b.confidence, a.confidence));
            selected = new ArrayList<>(selected.subList(0, MAX_BOARD_CARDS));
        }
        selected.sort(Comparator.comparingDouble(d -> d.bounds.centerX()));
        return selected;
    }

    private static Bitmap extractBoardCrop(Bitmap frame) {
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

        float sx = working.getWidth() / (float) REF_W;
        float sy = working.getHeight() / (float) REF_H;
        Rect scaled = new Rect(Math.round(BOARD_ROI.left * sx),
                Math.round(BOARD_ROI.top * sy), Math.round(BOARD_ROI.right * sx),
                Math.round(BOARD_ROI.bottom * sy));
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
