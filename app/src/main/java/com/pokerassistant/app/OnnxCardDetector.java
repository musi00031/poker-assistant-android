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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** YOLO detector for all 52 playing cards. */
final class OnnxCardDetector {
    private static final String TAG = "PokerCardModel";
    private static final int PAD = 114;
    private static final float MIN_CONFIDENCE = 0.12f;
    private static final float NMS_IOU = 0.45f;
    private static final int MAX_DETECTIONS = 12;

    // Class order matches xay6/playing-card-detector/data.yaml.
    private static final String[] LABELS = {
        "Ac","2c","3c","4c","5c","6c","7c","8c","9c","Tc","Jc","Qc","Kc",
        "Ad","2d","3d","4d","5d","6d","7d","8d","9d","Td","Jd","Qd","Kd",
        "Ah","2h","3h","4h","5h","6h","7h","8h","9h","Th","Jh","Qh","Kh",
        "As","2s","3s","4s","5s","6s","7s","8s","9s","Ts","Js","Qs","Ks"
    };

    static final class Detection {
        final String code;
        final RectF bounds;
        final float confidence;

        Detection(String code, RectF bounds, float confidence) {
            this.code = code;
            this.bounds = new RectF(bounds);
            this.confidence = confidence;
        }
    }

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
        Log.i(TAG, "Модель карт загружена: " + inputWidth + "x" + inputHeight);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    /** Single-card convenience method used by the fixed player-card crops. */
    synchronized String detect(Bitmap source) {
        List<Detection> detections = detectAll(source);
        return detections.isEmpty() ? null : detections.get(0).code;
    }

    /** Detects multiple cards and returns source-image coordinates, strongest first. */
    synchronized List<Detection> detectAll(Bitmap source) {
        if (source == null || source.isRecycled()
                || source.getWidth() < 2 || source.getHeight() < 2) {
            return Collections.emptyList();
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
                Log.w(TAG, "Модель вернула неподдерживаемый результат: outputCount=" + result.size() + ", outputType=" + (result.size() == 0 ? "empty" : result.get(0).getValue().getClass().getName()));
                return Collections.emptyList();
            }

            float[][][] output = (float[][][]) result.get(0).getValue();
            if (output.length == 0 || output[0].length == 0) return Collections.emptyList();
            float[][] matrix = output[0];

            final int channels = LABELS.length + 4;
            final boolean channelsFirst;
            if (matrix.length == channels) {
                channelsFirst = true;
            } else if (matrix[0].length == channels) {
                channelsFirst = false;
            } else {
                Log.e(TAG, "Неожиданный формат выхода модели: "
                        + matrix.length + "x" + matrix[0].length + "; ожидалось 56xN или Nx56");
                return Collections.emptyList();
            }

            int count = channelsFirst ? matrix[0].length : matrix.length;
            List<Detection> candidates = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                float cx = value(matrix, channelsFirst, 0, i);
                float cy = value(matrix, channelsFirst, 1, i);
                float w = value(matrix, channelsFirst, 2, i);
                float h = value(matrix, channelsFirst, 3, i);
                if (!finite(cx) || !finite(cy) || !finite(w) || !finite(h)
                        || w < inputWidth * 0.015f || h < inputHeight * 0.025f
                        || w > inputWidth * 1.10f || h > inputHeight * 1.10f) {
                    continue;
                }
                if (cx < left || cx > left + scaledW || cy < top || cy > top + scaledH) {
                    continue;
                }

                int bestClass = -1;
                float bestScore = 0f;
                for (int c = 0; c < LABELS.length; c++) {
                    float score = value(matrix, channelsFirst, c + 4, i);
                    if (finite(score) && score > bestScore) {
                        bestScore = score;
                        bestClass = c;
                    }
                }
                if (bestClass < 0 || bestScore < MIN_CONFIDENCE) continue;

                float x1 = clamp((cx - w / 2f - left) / scale, 0f, source.getWidth());
                float y1 = clamp((cy - h / 2f - top) / scale, 0f, source.getHeight());
                float x2 = clamp((cx + w / 2f - left) / scale, 0f, source.getWidth());
                float y2 = clamp((cy + h / 2f - top) / scale, 0f, source.getHeight());
                if (x2 <= x1 || y2 <= y1) continue;

                candidates.add(new Detection(LABELS[bestClass],
                        new RectF(x1, y1, x2, y2), bestScore));
            }

            candidates.sort((a, b) -> Float.compare(b.confidence, a.confidence));
            List<Detection> kept = new ArrayList<>();
            for (Detection candidate : candidates) {
                boolean overlaps = false;
                for (Detection accepted : kept) {
                    if (iou(candidate.bounds, accepted.bounds) > NMS_IOU) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) {
                    kept.add(candidate);
                    if (kept.size() >= MAX_DETECTIONS) break;
                }
            }
            Log.d(TAG, "Детектор: source=" + source.getWidth() + "x" + source.getHeight()
                    + ", model=" + inputWidth + "x" + inputHeight
                    + ", порог=" + MIN_CONFIDENCE + ", кандидатов после NMS=" + kept.size()
                    + (candidates.isEmpty() ? "" : ", лучший=" + candidates.get(0).code
                    + " confidence=" + candidates.get(0).confidence));
            return kept;
        } catch (Exception e) {
            Log.e(TAG, "Не удалось распознать карты", e);
            return Collections.emptyList();
        }
    }

    private static float value(float[][] matrix, boolean channelsFirst,
                               int channel, int detection) {
        return channelsFirst ? matrix[channel][detection] : matrix[detection][channel];
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - intersection;
        return union <= 0f ? 0f : intersection / union;
    }
}
