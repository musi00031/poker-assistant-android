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

public final class CardRecognizer implements AutoCloseable {

    public interface Callback {
        void onSuccess(List<RecognizedText> items, String fullText);
        void onFailure(Exception error);
    }

    public static final class RecognizedText {
        public final String text;
        public final Rect bounds;

        RecognizedText(String text, Rect bounds) {
            this.text = text;
            this.bounds = bounds;
        }

        @Override
        public String toString() {
            return text + " @ " + bounds;
        }
    }

    private final TextRecognizer recognizer =
            TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS
            );

    private boolean closed = false;
    private boolean processing = false;

    public synchronized boolean isProcessing() {
        return processing;
    }

    public void recognize(Bitmap bitmap, Callback callback) {
        if (bitmap == null || bitmap.isRecycled()) {
            callback.onFailure(
                    new IllegalArgumentException("Кадр пустой или уже освобождён")
            );
            return;
        }

        synchronized (this) {
            if (closed) {
                callback.onFailure(
                        new IllegalStateException("Распознаватель закрыт")
                );
                return;
            }

            if (processing) {
                return;
            }

            processing = true;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    List<RecognizedText> items = new ArrayList<>();

                    for (Text.TextBlock block : result.getTextBlocks()) {
                        Rect box = block.getBoundingBox();

                        if (box != null) {
                            items.add(
                                    new RecognizedText(
                                            block.getText(),
                                            new Rect(box)
                                    )
                            );
                        }
                    }

                    synchronized (CardRecognizer.this) {
                        processing = false;
                    }

                    callback.onSuccess(items, result.getText());
                })
                .addOnFailureListener(error -> {
                    synchronized (CardRecognizer.this) {
                        processing = false;
                    }

                    callback.onFailure(error);
                });
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            recognizer.close();
        }
    }
}
