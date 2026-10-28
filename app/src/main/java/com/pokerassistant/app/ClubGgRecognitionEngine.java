package com.pokerassistant.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fresh, model-independent ClubGG card recognition primitives.
 *
 * This class deliberately does not load the previous ONNX model. It detects
 * white card-like regions, creates stable corner crops, and classifies red/black
 * ink as a weak suit-color hint. Exact rank and suit are not guessed from color
 * alone; the UI must leave low-confidence candidates unconfirmed.
 *
 * Important: accurate 52-class recognition requires ClubGG screenshots/templates
 * or a model trained on the same deck and rendering. This class is the new
 * deterministic image pipeline foundation, not a claim of production accuracy.
 */
final class ClubGgRecognitionEngine {
    private ClubGgRecognitionEngine() {}

    enum InkColor { RED, BLACK, UNKNOWN }

    static final class CardRegion {
        final Rect bounds;
        final Rect rankCorner;
        final InkColor inkColor;
        final float whiteRatio;
        final float inkRatio;

        CardRegion(Rect bounds, Rect rankCorner, InkColor inkColor,
                   float whiteRatio, float inkRatio) {
            this.bounds = new Rect(bounds);
            this.rankCorner = new Rect(rankCorner);
            this.inkColor = inkColor;
            this.whiteRatio = whiteRatio;
            this.inkRatio = inkRatio;
        }
    }

    /** Scales user-defined normalized ROI to the current screenshot dimensions. */
    static Rect scaleNormalizedRoi(Rect normalized, int width, int height) {
        if (normalized == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid ROI or frame size");
        }
        int l = clamp(Math.round(normalized.left * width), 0, width - 1);
        int t = clamp(Math.round(normalized.top * height), 0, height - 1);
        int r = clamp(Math.round(normalized.right * width), l + 1, width);
        int b = clamp(Math.round(normalized.bottom * height), t + 1, height);
        return new Rect(l, t, r, b);
    }

    /**
     * Finds candidate card regions inside a selected ROI. Uses bright-pixel
     * connected components and aspect-ratio filtering, so it is independent of
     * fixed device coordinates. Busy table highlights may create false positives;
     * caller must validate the expected count and temporal stability.
     */
    static List<CardRegion> findCardRegions(Bitmap frame, Rect requestedRoi) {
        if (frame == null || frame.isRecycled() || requestedRoi == null) {
            return Collections.emptyList();
        }
        Rect roi = new Rect(requestedRoi);
        if (!roi.intersect(0, 0, frame.getWidth(), frame.getHeight())) {
            return Collections.emptyList();
        }

        int w = roi.width(), h = roi.height();
        int step = Math.max(1, Math.min(w, h) / 420);
        int gw = (w + step - 1) / step;
        int gh = (h + step - 1) / step;
        boolean[] mask = new boolean[gw * gh];

        for (int gy = 0; gy < gh; gy++) {
            int y = Math.min(roi.bottom - 1, roi.top + gy * step);
            for (int gx = 0; gx < gw; gx++) {
                int x = Math.min(roi.right - 1, roi.left + gx * step);
                int c = frame.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                mask[gy * gw + gx] = min >= 145 && max - min < 85;
            }
        }

        boolean[] seen = new boolean[mask.length];
        int[] queue = new int[mask.length];
        List<CardRegion> out = new ArrayList<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int minX = start % gw, maxX = minX;
            int minY = start / gw, maxY = minY;
            while (head < tail) {
                int idx = queue[head++];
                int x = idx % gw, y = idx / gw;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                int[] ns = {x > 0 ? idx - 1 : -1, x + 1 < gw ? idx + 1 : -1,
                        y > 0 ? idx - gw : -1, y + 1 < gh ? idx + gw : -1};
                for (int n : ns) {
                    if (n >= 0 && mask[n] && !seen[n]) {
                        seen[n] = true;
                        queue[tail++] = n;
                    }
                }
            }
            if (tail < 12) continue;
            int left = roi.left + minX * step;
            int top = roi.top + minY * step;
            int right = Math.min(roi.right, roi.left + (maxX + 1) * step);
            int bottom = Math.min(roi.bottom, roi.top + (maxY + 1) * step);
            int cw = right - left, ch = bottom - top;
            if (cw < 12 || ch < 18) continue;
            float aspect = (float) cw / ch;
            if (aspect < 0.38f || aspect > 1.05f) continue;
            Rect bounds = new Rect(left, top, right, bottom);
            Rect corner = new Rect(left, top,
                    Math.min(right, left + Math.max(12, cw / 3)),
                    Math.min(bottom, top + Math.max(18, ch / 3)));
            float white = measureWhiteRatio(frame, bounds);
            InkColor ink = classifyInk(frame, corner);
            float inkRatio = measureInkRatio(frame, corner);
            out.add(new CardRegion(bounds, corner, ink, white, inkRatio));
        }
        out.sort((a, b) -> {
            int y = Integer.compare(a.bounds.centerY(), b.bounds.centerY());
            return y != 0 ? y : Integer.compare(a.bounds.left, b.bounds.left);
        });
        return out;
    }

    static Bitmap cropRankCorner(Bitmap frame, CardRegion card, int scale) {
        if (frame == null || card == null || scale < 1) return null;
        Rect r = new Rect(card.rankCorner);
        if (!r.intersect(0, 0, frame.getWidth(), frame.getHeight())) return null;
        Bitmap crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height());
        if (scale == 1) return crop;
        Bitmap scaled = Bitmap.createScaledBitmap(crop, crop.getWidth() * scale,
                crop.getHeight() * scale, true);
        crop.recycle();
        return scaled;
    }

    private static float measureWhiteRatio(Bitmap b, Rect r) {
        int total = 0, white = 0;
        for (int y = r.top; y < r.bottom; y += 2) {
            for (int x = r.left; x < r.right; x += 2) {
                int c = b.getPixel(x, y);
                int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                if (min > 165) white++;
                total++;
            }
        }
        return total == 0 ? 0f : (float) white / total;
    }

    private static float measureInkRatio(Bitmap b, Rect r) {
        int total = 0, ink = 0;
        for (int y = r.top; y < r.bottom; y++) {
            for (int x = r.left; x < r.right; x++) {
                int c = b.getPixel(x, y);
                int max = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
                int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                if (max < 150 || max - min > 65) ink++;
                total++;
            }
        }
        return total == 0 ? 0f : (float) ink / total;
    }

    private static InkColor classifyInk(Bitmap b, Rect r) {
        int red = 0, black = 0;
        for (int y = r.top; y < r.bottom; y++) {
            for (int x = r.left; x < r.right; x++) {
                int c = b.getPixel(x, y);
                int rr = Color.red(c), gg = Color.green(c), bb = Color.blue(c);
                if (rr > 105 && rr > gg * 1.22f && rr > bb * 1.12f) red++;
                else if (Math.max(rr, Math.max(gg, bb)) < 135) black++;
            }
        }
        if (red > black * 1.20f && red > 4) return InkColor.RED;
        if (black > red * 1.20f && black > 4) return InkColor.BLACK;
        return InkColor.UNKNOWN;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
