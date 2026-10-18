package com.pokerassistant.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Experimental community-card recognition for the portrait ClubGG layout.
 * Cards are detected as objects in a broad central-table ROI, then grouped by
 * their vertical centers and ordered left-to-right. The ROI is scaled from the
 * 1220x2712 reference screenshot and should be rechecked if the table layout changes.
 */
final class BoardCardRecognizer {
    private static final String TAG = "PokerBoardModel";
    private static final int REF_W = 1220;
    private static final int REF_H = 2712;

    // Broad table-center ROI: excludes the fixed hero-card row at the bottom.
    private static final Rect BOARD_ROI = new Rect(32, 704, 1136, 1824);
    private static final float ROW_Y_TOLERANCE = 0.10f;
    private static final int MIN_BOARD_CARDS = 3;
    private static final int MAX_BOARD_CARDS = 5;

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    interface Callback {
        void onComplete(List<PokerEngine.Card> cards, String diagnostic);
    }

    private BoardCardRecognizer() {}

    static void recognize(Context context, Bitmap frame, Callback callback) {
        Bitmap crop = extractBoardCrop(frame);
        if (crop == null) {
            callback.onComplete(Collections.emptyList(),
                    "Область общих карт вне кадра");
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                OnnxCardDetector detector = OnnxCardDetector.get(context);
                List<OnnxCardDetector.Detection> detections = detector.detectAll(crop);
                List<OnnxCardDetector.Detection> row = chooseBoardRow(
                        detections, crop.getHeight());

                List<PokerEngine.Card> cards = new ArrayList<>();
                for (OnnxCardDetector.Detection detection : row) {
                    try {
                        cards.add(PokerEngine.Card.parse(detection.code));
                    } catch (IllegalArgumentException ignored) {
                        // A malformed class label must never enter the live calculation.
                    }
                }

                if (cards.size() < MIN_BOARD_CARDS || cards.size() > MAX_BOARD_CARDS
                        || hasDuplicates(cards)) {
                    callback.onComplete(Collections.emptyList(),
                            "Модель видит " + cards.size()
                                    + " общих карт; нужно уверенно определить 3–5");
                } else {
                    StringBuilder summary = new StringBuilder("Общие карты: ");
                    for (PokerEngine.Card card : cards) {
                        if (summary.charAt(summary.length() - 1) != ' ') summary.append(' ');
                        summary.append(card.toString());
                    }
                    Log.i(TAG, summary.toString());
                    callback.onComplete(cards, summary.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка распознавания общих карт", e);
                callback.onComplete(Collections.emptyList(),
                        "Модель общих карт недоступна: " + e.getClass().getSimpleName());
            } finally {
                if (!crop.isRecycled()) crop.recycle();
            }
        });
    }

    private static Bitmap extractBoardCrop(Bitmap frame) {
        if (frame == null || frame.isRecycled()
                || frame.getWidth() < 2 || frame.getHeight() < 2) {
            return null;
        }

        Bitmap working = frame;
        boolean rotated = false;
        if (frame.getWidth() > frame.getHeight()) {
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(90f);
            working = Bitmap.createBitmap(
                    frame, 0, 0, frame.getWidth(), frame.getHeight(), matrix, true);
            rotated = working != frame;
        }

        float sx = working.getWidth() / (float) REF_W;
        float sy = working.getHeight() / (float) REF_H;
        Rect scaled = new Rect(
                Math.round(BOARD_ROI.left * sx),
                Math.round(BOARD_ROI.top * sy),
                Math.round(BOARD_ROI.right * sx),
                Math.round(BOARD_ROI.bottom * sy));
        if (!scaled.intersect(0, 0, working.getWidth(), working.getHeight())
                || scaled.width() < 20 || scaled.height() < 20) {
            if (rotated && !working.isRecycled()) working.recycle();
            return null;
        }

        Bitmap crop = Bitmap.createBitmap(
                working, scaled.left, scaled.top, scaled.width(), scaled.height());
        if (rotated && !working.isRecycled()) working.recycle();
        return crop;
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
        for (int start = 0; start < byY.size(); start++) {
            float anchorY = byY.get(start).bounds.centerY();
            List<OnnxCardDetector.Detection> cluster = new ArrayList<>();
            float confidenceSum = 0f;
            for (OnnxCardDetector.Detection candidate : byY) {
                if (Math.abs(candidate.bounds.centerY() - anchorY) <= tolerance) {
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

    private static boolean hasDuplicates(List<PokerEngine.Card> cards) {
        for (int i = 0; i < cards.size(); i++) {
            for (int j = i + 1; j < cards.size(); j++) {
                if (cards.get(i).equals(cards.get(j))) return true;
            }
        }
        return false;
    }
}
