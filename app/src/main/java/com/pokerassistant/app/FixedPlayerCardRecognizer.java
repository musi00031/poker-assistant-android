package com.pokerassistant.app;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

public final class FixedPlayerCardRecognizer {

    private FixedPlayerCardRecognizer() {}

    public static final int REFERENCE_WIDTH = 1220;
    public static final int REFERENCE_HEIGHT = 2712;

    // Проверенные фиксированные координаты карт игрока.
    private static final Rect LEFT_CARD =
            new Rect(50, 1945, 170, 2140);

    private static final Rect RIGHT_CARD =
            new Rect(160, 1945, 280, 2140);

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
        public final String text;
        public final String error;

        private CardTextResult(
                String position,
                Rect bounds,
                String text,
                String error
        ) {
            this.position = position;
            this.bounds = new Rect(bounds);
            this.text = text;
            this.error = error;
        }

        @Override
        public String toString() {
            return position
                    + " | область=" + bounds.flattenToString()
                    + " | текст="
                    + (error != null
                        ? "ОШИБКА: " + error
                        : (text.isEmpty() ? "<не распознан>" : text));
        }
    }

    public interface Callback {
        void onComplete(List<CardTextResult> results);
    }

    public static List<CardRegion> extract(Bitmap frame) {
        List<CardRegion> result = new ArrayList<>();

        if (frame == null || frame.isRecycled()
                || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return result;
        }

        addRegion(result, frame, "PLAYER_LEFT", LEFT_CARD);
        addRegion(result, frame, "PLAYER_RIGHT", RIGHT_CARD);

        return result;
    }

    private static void addRegion(
            List<CardRegion> result,
            Bitmap frame,
            String position,
            Rect referenceBounds
    ) {
        float sx = frame.getWidth() / (float) REFERENCE_WIDTH;
        float sy = frame.getHeight() / (float) REFERENCE_HEIGHT;

        Rect bounds = new Rect(
                Math.round(referenceBounds.left * sx),
                Math.round(referenceBounds.top * sy),
                Math.round(referenceBounds.right * sx),
                Math.round(referenceBounds.bottom * sy)
        );

        if (!bounds.intersect(
                    0, 0, frame.getWidth(), frame.getHeight())
                || bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }

        Bitmap crop = Bitmap.createBitmap(
                frame,
                bounds.left,
                bounds.top,
                bounds.width(),
                bounds.height()
        );

        // Увеличиваем изображение для улучшения распознавания мелких символов.
        Bitmap enlarged = Bitmap.createScaledBitmap(
                crop,
                crop.getWidth() * 4,
                crop.getHeight() * 4,
                true
        );

        if (enlarged != crop) {
            crop.recycle();
        }

        result.add(new CardRegion(position, bounds, enlarged));
    }

    public static void recognize(Bitmap frame, Callback callback) {
        List<CardRegion> regions = extract(frame);

        if (regions.isEmpty()) {
            callback.onComplete(new ArrayList<>());
            return;
        }

        TextRecognizer recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
        );

        List<CardTextResult> results = new ArrayList<>();

        processNext(regions, 0, results, recognizer, callback);
    }

    private static void processNext(
            List<CardRegion> regions,
            int index,
            List<CardTextResult> results,
            TextRecognizer recognizer,
            Callback callback
    ) {
        if (index >= regions.size()) {
            releaseRegions(regions);
            recognizer.close();
            callback.onComplete(results);
            return;
        }

        CardRegion region = regions.get(index);

        try {
            recognizer.process(InputImage.fromBitmap(region.image, 0))
                    .addOnSuccessListener(textResult -> {
                        String text = textResult.getText();

                        android.util.Log.d(
                                "PokerCardOCR",
                                region.position + " OCR: "
                                        + (text == null || text.trim().isEmpty()
                                        ? "<пусто>"
                                        : text.trim().replace("\\n", " | "))
                        );

                        results.add(new CardTextResult(
                                region.position,
                                region.bounds,
                                text == null ? "" : text.trim(),
                                null
                        ));

                        recycle(region.image);
                        processNext(
                                regions, index + 1,
                                results, recognizer, callback
                        );
                    })
                    .addOnFailureListener(error -> {
                        results.add(new CardTextResult(
                                region.position,
                                region.bounds,
                                "",
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage()
                        ));

                        recycle(region.image);
                        processNext(
                                regions, index + 1,
                                results, recognizer, callback
                        );
                    });
        } catch (Exception error) {
            results.add(new CardTextResult(
                    region.position,
                    region.bounds,
                    "",
                    error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage()
            ));

            recycle(region.image);
            processNext(
                    regions, index + 1,
                    results, recognizer, callback
            );
        }
    }

    private static void releaseRegions(List<CardRegion> regions) {
        for (CardRegion region : regions) {
            recycle(region.image);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
