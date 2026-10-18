package com.pokerassistant.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * Finds a complete playing-card object inside one fixed player-card crop.
 * The model is a YOLO object detector, not a generic OCR engine: class scores
 * are accepted only when the same prediction contains a plausible card box.
 */
final class OnnxCardDetector {
    private static final String TAG = "PokerCardModel";
    private static final int PAD = 114;
    private static final float MIN_CONFIDENCE = 0.30f;

    // 52 classes: clubs, diamonds, hearts, spades; ranks A, 2..10, J, Q, K.
    private static final String[] LABELS = {
        "Ac","2c","3c","4c","5c","6c","7c","8c","9c","Tc","Jc","Qc","Kc",
        "Ad","2d","3d","4d","5d","6d","7d","8d","9d","Td","Jd","Qd","Kd",
        "Ah","2h","3h","4h","5h","6h","7h","8h","9h","Th","Jh","Qh","Kh",
        "As","2s","3s","4s","5s","6s","7s","8s","9s","Ts","Js","Qs","Ks"
    };

    private static volatile OnnxCardDetector instance;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final int inputWidth;
    private final int inputHeight;

    static synchronized OnnxCardDetector get(Context context) throws Exception {
        if (instance == null) instance = new OnnxCardDetector(context.getApplicationContext());
        return instance;
    }

    private OnnxCardDetector(Context context) throws Exception {
        environment = OrtEnvironment.getEnvironment();
        try (InputStream in = context.getAssets().open("playing_card_detector.onnx")) {
            session = environment.createSession(readAll(in), new OrtSession.SessionOptions());
        }
        inputName = session.getInputNames().iterator().next();
        TensorInfo info = (TensorInfo) session.getInputInfo().get(inputName).getInfo();
        long[] shape = info.getShape();
        if (shape.length != 4 || shape[1] != 3 || shape[2] <= 0 || shape[3] <= 0) {
            throw new IllegalStateException("Не удалось определить размер модели карт");
        }
        inputHeight = (int) shape[2];
        inputWidth = (int) shape[3];
        Log.i(TAG, "Модель карт загружена");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    /** Returns a code such as Th or 6s only when a card-shaped detection exists. */
    synchronized String detect(Bitmap source) {
        if (source == null || source.isRecycled() || source.getWidth() < 2 || source.getHeight() < 2) {
            return null;
        }

        Bitmap modelImage = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(modelImage);
        canvas.drawColor(Color.rgb(PAD, PAD, PAD));
        float scale = Math.min(inputWidth / (float) source.getWidth(),
                inputHeight / (float) source.getHeight());
        float scaledW = source.getWidth() * scale;
        float scaledH = source.getHeight() * scale;
        float left = (inputWidth - scaledW) / 2f;
        float top = (inputHeight - scaledH) / 2f;
        canvas.drawBitmap(source, null,
                new RectF(left, top, left + scaledW, top + scaledH),
                new Paint(Paint.FILTER_BITMAP_FLAG));

        int[] pixels = new int[inputWidth * inputHeight];
        modelImage.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        modelImage.recycle();

        int plane = pixels.length;
        FloatBuffer tensorData = FloatBuffer.allocate(3 * plane);
        for (int i = 0; i < plane; i++) {
            int p = pixels[i];
            tensorData.put(i, ((p >> 16) & 255) / 255f);
            tensorData.put(plane + i, ((p >> 8) & 255) / 255f);
            tensorData.put(2 * plane + i, (p & 255) / 255f);
        }
        tensorData.rewind();

        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, tensorData,
                new long[]{1, 3, inputHeight, inputWidth});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            if (result.size() == 0 || !(result.get(0).getValue() instanceof float[][][])) {
                Log.w(TAG, "Модель вернула неподдерживаемый результат");
                return null;
            }

            float[][][] output = (float[][][]) result.get(0).getValue();
            if (output.length == 0 || output[0].length == 0) return null;
            float[][] matrix = output[0];

            final int channels = LABELS.length + 4;
            final boolean channelsFirst;
            if (matrix.length == channels) {
                channelsFirst = true;   // [1, 56, detections]
            } else if (matrix[0].length == channels) {
                channelsFirst = false;  // [1, detections, 56]
            } else {
                Log.w(TAG, "Модель карт вернула неожиданный формат");
                return null;
            }

            int detections = channelsFirst ? matrix[0].length : matrix.length;
            float bestScore = 0f;
            int bestClass = -1;

            for (int i = 0; i < detections; i++) {
                float cx = value(matrix, channelsFirst, 0, i);
                float cy = value(matrix, channelsFirst, 1, i);
                float w  = value(matrix, channelsFirst, 2, i);
                float h  = value(matrix, channelsFirst, 3, i);

                // Ignore empty/noisy boxes. The crop is meant to contain one whole card.
                if (!finite(cx) || !finite(cy) || !finite(w) || !finite(h)
                        || w < inputWidth * 0.08f || h < inputHeight * 0.12f
                        || w > inputWidth * 1.10f || h > inputHeight * 1.10f) {
                    continue;
                }
                float x1 = cx - w / 2f, x2 = cx + w / 2f;
                float y1 = cy - h / 2f, y2 = cy + h / 2f;
                if (x2 <= 0 || y2 <= 0 || x1 >= inputWidth || y1 >= inputHeight) continue;

                // Card center should lie inside the visible crop, not in letterbox padding.
                if (cx < left || cx > left + scaledW || cy < top || cy > top + scaledH) continue;

                for (int c = 0; c < LABELS.length; c++) {
                    float score = value(matrix, channelsFirst, c + 4, i);
                    if (finite(score) && score > bestScore) {
                        bestScore = score;
                        bestClass = c;
                    }
                }
            }

            if (bestClass >= 0) {
                Log.d(TAG, "Карта-кандидат " + LABELS[bestClass]
                        + ", уверенность " + Math.round(bestScore * 100) + "%");
            }
            return bestClass >= 0 && bestScore >= MIN_CONFIDENCE ? LABELS[bestClass] : null;
        } catch (Exception e) {
            Log.e(TAG, "Не удалось распознать карту", e);
            return null;
        }
    }

    private static float value(float[][] matrix, boolean channelsFirst, int channel, int detection) {
        return channelsFirst ? matrix[channel][detection] : matrix[detection][channel];
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
