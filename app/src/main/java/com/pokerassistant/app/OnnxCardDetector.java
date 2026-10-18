package com.pokerassistant.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device YOLO playing-card classifier. Inference is performed on each
 * fixed hole-card crop, so the detector does not have to locate the player's
 * cards elsewhere on the ClubGG screen.
 */
final class OnnxCardDetector {
    private static final String TAG = "PokerCardModel";
    private static final int PAD = 114;
    private static final float MIN_CONFIDENCE = 0.22f;

    // Order matches xay6/playing-card-detector data.yaml.
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
            byte[] model = readAll(in);
            session = environment.createSession(model, new OrtSession.SessionOptions());
        }
        inputName = session.getInputNames().iterator().next();
        TensorInfo info = (TensorInfo) session.getInputInfo().get(inputName).getInfo();
        long[] shape = info.getShape();
        if (shape.length != 4 || shape[1] != 3 || shape[2] <= 0 || shape[3] <= 0) {
            throw new IllegalStateException("Неожиданный вход ONNX: " + java.util.Arrays.toString(shape));
        }
        inputHeight = (int) shape[2];
        inputWidth = (int) shape[3];
        Log.i(TAG, "Модель загружена; input=" + inputWidth + "x" + inputHeight);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    /** Returns a class code such as As or 10h, or null when confidence is low. */
    synchronized String detect(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        Bitmap canvasBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        canvas.drawColor(Color.rgb(PAD, PAD, PAD));
        float scale = Math.min(inputWidth / (float) source.getWidth(),
                inputHeight / (float) source.getHeight());
        float scaledW = source.getWidth() * scale;
        float scaledH = source.getHeight() * scale;
        float left = (inputWidth - scaledW) / 2f;
        float top = (inputHeight - scaledH) / 2f;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null,
                new RectF(left, top, left + scaledW, top + scaledH), paint);

        int[] pixels = new int[inputWidth * inputHeight];
        canvasBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        canvasBitmap.recycle();

        FloatBuffer tensorData = FloatBuffer.allocate(3 * pixels.length);
        int plane = pixels.length;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            tensorData.put(i, ((p >> 16) & 255) / 255f);
            tensorData.put(plane + i, ((p >> 8) & 255) / 255f);
            tensorData.put(2 * plane + i, (p & 255) / 255f);
        }
        tensorData.rewind();

        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, tensorData,
                new long[]{1, 3, inputHeight, inputWidth});
             OrtSession.Result result = session.run(java.util.Collections.singletonMap(inputName, tensor))) {
            if (result.size() == 0 || !(result.get(0).getValue() instanceof float[][][])) {
                Log.w(TAG, "Выход модели не является float[][][]");
                return null;
            }
            float[][][] output = (float[][][]) result.get(0).getValue();
            if (output.length == 0 || output[0].length == 0) return null;
            float[][] matrix = output[0];
            int dims = matrix.length;
            int count = matrix[0].length;
            boolean channelsFirst = dims == LABELS.length + 4;
            if (!channelsFirst && count == LABELS.length + 4) {
                int tmp = dims; dims = count; count = tmp;
            }
            if (dims < LABELS.length + 4) {
                Log.w(TAG, "Неизвестная форма выхода модели: " + matrix.length + "x" + matrix[0].length);
                return null;
            }

            float best = 0f;
            int bestClass = -1;
            for (int i = 0; i < count; i++) {
                float candidate = 0f;
                int candidateClass = -1;
                for (int c = 0; c < LABELS.length; c++) {
                    float score = channelsFirst ? matrix[c + 4][i] : matrix[i][c + 4];
                    if (score > candidate) { candidate = score; candidateClass = c; }
                }
                if (candidate > best) { best = candidate; bestClass = candidateClass; }
            }
            Log.d(TAG, "Кандидат карты: " + (bestClass >= 0 ? LABELS[bestClass] : "нет")
                    + ", confidence=" + best);
            return best >= MIN_CONFIDENCE && bestClass >= 0 ? LABELS[bestClass] : null;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инференса ONNX", e);
            return null;
        }
    }
}
