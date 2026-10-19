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
    private static volatile String lastDiagnostic = "Модель ещё не запускалась";

    static String getDiagnostic() {
        return lastDiagnostic;
    }
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final int inputWidth;
    private final int inputHeight;
    // Fallback preprocessing modes are attempted only when the standard RGB/255 path finds no cards.
    private int preprocessingMode = 0;

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
        lastDiagnostic = "Модель загружена; вход " + inputWidth + "x" + inputHeight;
        Log.i(TAG, lastDiagnostic);
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
        if (detections.isEmpty()) return null;
        Detection best = detections.get(0);
        // On tiny fixed card crops a weak detection is more dangerous than no result:
        // send ambiguous crops to the independent OCR/color path instead.
        return best.confidence >= 0.30f ? best.code : null;
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
        long redSum = 0, greenSum = 0, blueSum = 0;
        for (int p : pixels) { redSum += (p >> 16) & 255; greenSum += (p >> 8) & 255; blueSum += p & 255; }
        FloatBuffer tensorData = FloatBuffer.allocate(3 * plane);
        for (int i = 0; i < plane; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 255;
            int g = (p >> 8) & 255;
            int b = p & 255;
            if (preprocessingMode == 1 || preprocessingMode == 3) {
                int swap = r; r = b; b = swap;
            }
            float divisor = preprocessingMode >= 2 ? 1f : 255f;
            tensorData.put(i, r / divisor);
            tensorData.put(plane + i, g / divisor);
            tensorData.put(2 * plane + i, b / divisor);
        }
        tensorData.rewind();

        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, tensorData,
                new long[]{1, 3, inputHeight, inputWidth});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            if (result.size() == 0 || !(result.get(0).getValue() instanceof float[][][])) {
                lastDiagnostic = "ВЫХОД МОДЕЛИ: неподдерживаемый тип; outputs=" + result.size() + (result.size() == 0 ? "" : ", type=" + result.get(0).getValue().getClass().getName());
                Log.w(TAG, lastDiagnostic);
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
                lastDiagnostic = "ФОРМАТ МОДЕЛИ НЕ ПОДХОДИТ: " + matrix.length + "x" + matrix[0].length + ", ожидалось 56xN/Nx56";
                Log.e(TAG, lastDiagnostic);
                return Collections.emptyList();
            }

            int count = channelsFirst ? matrix[0].length : matrix.length;
            float maxClassScore = -Float.MAX_VALUE;
            float minClassScore = Float.MAX_VALUE;
            float maxCoord = -Float.MAX_VALUE;
            float minCoord = Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < LABELS.length; c++) {
                    float score = value(matrix, channelsFirst, c + 4, i);
                    if (finite(score)) { if (score > maxClassScore) maxClassScore = score; if (score < minClassScore) minClassScore = score; }
                }
                for (int c = 0; c < 4; c++) {
                    float coord = value(matrix, channelsFirst, c, i);
                    if (finite(coord)) { if (coord > maxCoord) maxCoord = coord; if (coord < minCoord) minCoord = coord; }
                }
            }
            // Track raw class peaks before any box/confidence filtering. This is
            // critical for distinguishing a bad crop/preprocessing from a threshold issue.
            float[] topScores = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            int[] topClasses = {-1, -1, -1};
            int[] topIndexes = {-1, -1, -1};
            for (int i = 0; i < count; i++) {
                float best = -Float.MAX_VALUE;
                int bestClass = -1;
                for (int c = 0; c < LABELS.length; c++) {
                    float score = value(matrix, channelsFirst, c + 4, i);
                    if (finite(score) && score > best) {
                        best = score;
                        bestClass = c;
                    }
                }
                for (int rank = 0; rank < topScores.length; rank++) {
                    if (best > topScores[rank]) {
                        for (int move = topScores.length - 1; move > rank; move--) {
                            topScores[move] = topScores[move - 1];
                            topClasses[move] = topClasses[move - 1];
                            topIndexes[move] = topIndexes[move - 1];
                        }
                        topScores[rank] = best;
                        topClasses[rank] = bestClass;
                        topIndexes[rank] = i;
                        break;
                    }
                }
            }
            StringBuilder peakDiagnostic = new StringBuilder(" пики=");
            for (int i = 0; i < topScores.length; i++) {
                if (i > 0) peakDiagnostic.append(",");
                peakDiagnostic.append(topClasses[i] >= 0 ? LABELS[topClasses[i]] : "?")
                        .append("@").append(String.format(java.util.Locale.US, "%.8f", topScores[i]))
                        .append("#").append(topIndexes[i]);
            }

            int validBoxes = 0;
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

                float aspect = w / h;
                // Playing cards are taller than they are wide; reject implausible background boxes.
                if (aspect < 0.35f || aspect > 1.50f) continue;

                validBoxes++;
                int bestClass = -1;
                float bestScore = 0f;
                for (int c = 0; c < LABELS.length; c++) {
                    float score = value(matrix, channelsFirst, c + 4, i);
                    if (finite(score) && score > bestScore) {
                        bestScore = score;
                        bestClass = c;
                    }
                }
                // Preprocessing fallbacks may change scores, but a near-zero score is
                // not evidence of a card. Never relax the confidence floor by mode:
                // weak detections on the felt cause confidently wrong poker calculations.
                float requiredConfidence = MIN_CONFIDENCE;
                if (bestClass < 0 || bestScore < requiredConfidence) continue;

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
            lastDiagnostic = "ONNX: кадр " + source.getWidth() + "x" + source.getHeight()
                    + "; выход " + matrix.length + "x" + matrix[0].length
                    + "; score-range=" + String.format(java.util.Locale.US, "%.6f..%.6f", minClassScore, maxClassScore)
                    + "; coords-range=" + String.format(java.util.Locale.US, "%.2f..%.2f", minCoord, maxCoord)
                    + "; RGBavg=" + String.format(java.util.Locale.US, "%.0f/%.0f/%.0f", redSum/(double)plane, greenSum/(double)plane, blueSum/(double)plane)
                    + "; bbox=" + validBoxes + "; кандидаты=" + candidates.size()
                    + "; после NMS=" + kept.size() + peakDiagnostic
                    + (candidates.isEmpty() ? "" : "; лучший=" + candidates.get(0).code
                    + " (" + String.format(java.util.Locale.US, "%.3f", candidates.get(0).confidence) + ")");
            String[] preprocessingNames = {"RGB/255", "BGR/255", "RGB/0-255", "BGR/0-255"};
            lastDiagnostic += "; preprocessing=" + preprocessingNames[preprocessingMode];
            Log.d(TAG, lastDiagnostic);

            // Weak boxes used to suppress fallback even though hero cards
            // require >=0.30 confidence. Retry other tensor preprocessing modes
            // when the current mode finds no box OR only weak boxes.
            if ((kept.isEmpty() || kept.get(0).confidence < 0.30f)
                    && preprocessingMode < 3) {
                int previousMode = preprocessingMode;
                String previousDiagnostic = lastDiagnostic;
                float previousBest = kept.isEmpty() ? 0f : kept.get(0).confidence;
                preprocessingMode = previousMode + 1;
                List<Detection> retry = detectAll(source);
                String retryDiagnostic = lastDiagnostic;
                preprocessingMode = previousMode;

                float retryBest = retry.isEmpty() ? 0f : retry.get(0).confidence;
                if (!retry.isEmpty() && retryBest > previousBest) {
                    lastDiagnostic = "Лучшая альтернативная обработка ("
                            + preprocessingNames[Math.min(3, previousMode + 1)] + "): "
                            + retry.size() + " карт; " + retryDiagnostic;
                    Log.i(TAG, lastDiagnostic);
                    return retry;
                }
                if (kept.isEmpty() && !retry.isEmpty()) {
                    lastDiagnostic = "Найдены карты альтернативной обработкой: "
                            + retry.size() + "; " + retryDiagnostic;
                    Log.i(TAG, lastDiagnostic);
                    return retry;
                }
                if (kept.isEmpty()) {
                    lastDiagnostic = "Карты не найдены ни одним режимом обработки. "
                            + previousDiagnostic + " | fallback: " + retryDiagnostic;
                    Log.w(TAG, lastDiagnostic);
                } else {
                    lastDiagnostic = previousDiagnostic + " | альтернативы не лучше ("
                            + String.format(java.util.Locale.US, "%.3f", retryBest) + ")";
                }
            }
            return kept;
        } catch (Exception e) {
            lastDiagnostic = "ОШИБКА ONNX: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            Log.e(TAG, lastDiagnostic, e);
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
