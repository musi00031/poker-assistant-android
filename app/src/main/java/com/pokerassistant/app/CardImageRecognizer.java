package com.pokerassistant.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CardImageRecognizer {

    private CardImageRecognizer() {}

    // Ранг распознаётся независимо от масти.
    private static final Pattern RANK_PATTERN =
            Pattern.compile("(?i)(?<![A-Z0-9])(10|[2-9TJQKA])(?![A-Z0-9])");

    // Масть может быть распознана отдельно от ранга.
    private static final Pattern SUIT_PATTERN =
            Pattern.compile("(?i)[♠♤♣♧♥♡♦♢SHDC]");

    private static final Pattern COMBINED_PATTERN =
            Pattern.compile("(?i)(10|[2-9TJQKA])\\s*([♠♤♣♧♥♡♦♢SHDC])");

    public static final class Result {
        public final String rank;
        public final String suit;
        public final String card;
        public final float confidence;
        public final boolean confirmed;
        public final String source;
        public final Rect bounds;

        Result(
                String rank,
                String suit,
                float confidence,
                boolean confirmed,
                String source
        ) {
            this(rank, suit, confidence, confirmed, source, null);
        }

        Result(
                String rank,
                String suit,
                float confidence,
                boolean confirmed,
                String source,
                Rect bounds
        ) {
            this.rank = rank;
            this.suit = suit;
            this.card = suit == null || suit.isEmpty()
                    ? rank + "?"
                    : rank + suit;
            this.confidence = confidence;
            this.confirmed = confirmed;
            this.source = source;
            this.bounds = bounds == null ? null : new Rect(bounds);
        }

        @Override
        public String toString() {
            String position = bounds == null
                    ? "координаты неизвестны"
                    : "x=" + bounds.left + ".." + bounds.right
                    + ", y=" + bounds.top + ".." + bounds.bottom;

            return card
                    + " (" + Math.round(confidence * 100) + "%)"
                    + (confirmed ? "" : " — не подтверждена")
                    + " [" + source + "; " + position + "]";
        }
    }

    public static List<Result> recognize(
            Bitmap bitmap,
            List<CardRecognizer.RecognizedText> textItems,
            String fullText
    ) {
        Map<String, Result> unique = new LinkedHashMap<>();

        if (bitmap == null || bitmap.isRecycled()) {
            return new ArrayList<>();
        }

        if (textItems != null) {
            for (CardRecognizer.RecognizedText item : textItems) {
                if (item == null || item.text == null) {
                    continue;
                }

                String text = item.text.trim();
                if (text.isEmpty()) {
                    continue;
                }

                // Этап 1: сначала ищем полную комбинацию ранга и масти.
                Matcher combined = COMBINED_PATTERN.matcher(text);

                while (combined.find()) {
                    addCandidate(
                            unique,
                            normalizeRank(combined.group(1)),
                            normalizeSuit(combined.group(2)),
                            0.70f,
                            false,
                            "OCR-комбинация",
                            item.text,
                            item.bounds
                    );
                }

                // Этап 2: ищем ранг независимо от масти.
                Matcher ranks = RANK_PATTERN.matcher(text);

                while (ranks.find()) {
                    String rank = normalizeRank(ranks.group(1));
                    if (rank == null) {
                        continue;
                    }

                    String suit = findSuitNearRank(text, ranks.start(), ranks.end());

                    addCandidate(
                            unique,
                            rank,
                            suit,
                            suit.isEmpty() ? 0.45f : 0.65f,
                            false,
                            suit.isEmpty() ? "OCR-ранг" : "OCR-ранг+масть",
                            item.text,
                            item.bounds
                    );
                }
            }
        }

        // Этап 3: резервный разбор общего OCR-текста.
        // Не считаем его независимым подтверждением цвета или расположения.
        if (fullText != null && !fullText.trim().isEmpty()) {
            Matcher combined = COMBINED_PATTERN.matcher(fullText);

            while (combined.find()) {
                addCandidate(
                        unique,
                        normalizeRank(combined.group(1)),
                        normalizeSuit(combined.group(2)),
                        0.50f,
                        false,
                        "Полный OCR",
                        fullText,
                        null
                );
            }

            Matcher ranks = RANK_PATTERN.matcher(fullText);

            while (ranks.find()) {
                String rank = normalizeRank(ranks.group(1));
                if (rank == null) {
                    continue;
                }

                String suit = findSuitNearRank(
                        fullText,
                        ranks.start(),
                        ranks.end()
                );

                addCandidate(
                        unique,
                        rank,
                        suit,
                        0.40f,
                        false,
                        "Резервный OCR",
                        fullText,
                        null
                );
            }
        }

        return new ArrayList<>(unique.values());
    }

    private static void addCandidate(
            Map<String, Result> unique,
            String rank,
            String suit,
            float confidence,
            boolean confirmed,
            String source,
            String originalText,
            Rect bounds
    ) {
        if (rank == null) {
            return;
        }

        if (suit == null) {
            suit = "";
        }

        String key = rank + (suit.isEmpty() ? "?" : suit);

        Result existing = unique.get(key);

        if (existing == null || confidence > existing.confidence) {
            unique.put(
                    key,
                    new Result(
                            rank, suit, confidence, confirmed, source, bounds
                    )
            );
        }
    }

    private static String findSuitNearRank(
            String text,
            int rankStart,
            int rankEnd
    ) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // Ищем масть только рядом с рангом, а не во всём блоке.
        int left = Math.max(0, rankStart - 3);
        int right = Math.min(text.length(), rankEnd + 4);

        String nearby = text.substring(left, right);
        Matcher matcher = SUIT_PATTERN.matcher(nearby);

        while (matcher.find()) {
            String token = matcher.group();

            // Не принимаем букву из самого ранга за масть.
            int absolutePosition = left + matcher.start();

            // Буквы ранга не должны становиться обозначением масти.
            if (absolutePosition >= rankStart
                    && absolutePosition < rankEnd) {
                continue;
            }

            // Не принимаем далёкие символы из соседних элементов OCR.
            int distance = absolutePosition < rankStart
                    ? rankStart - absolutePosition
                    : absolutePosition - rankEnd;

            if (distance > 2) {
                continue;
            }

            String suit = normalizeSuit(token);
            if (!suit.isEmpty()) {
                return suit;
            }
        }

        return "";
    }

    private static String normalizeRank(String value) {
        if (value == null) {
            return null;
        }

        String rank = value.trim().toUpperCase();

        if (rank.equals("10")) {
            return "T";
        }

        // Принимаем только один корректный ранг карты.
        // Посторонние числа и многосимвольные фрагменты интерфейса отсекаются.
        return rank.matches("[2-9TJQKA]") ? rank : null;
    }

    private static String normalizeSuit(String value) {
        if (value == null) {
            return "";
        }

        switch (value.trim().toLowerCase()) {
            case "♠":
            case "♤":
            case "s":
                return "s";

            case "♥":
            case "♡":
            case "h":
                return "h";

            case "♦":
            case "♢":
            case "d":
                return "d";

            case "♣":
            case "♧":
            case "c":
                return "c";

            default:
                return "";
        }
    }

    /**
     * Цветовая проверка пока диагностическая.
     * Анализировать весь OCR-прямоугольник для определения масти
     * ненадёжно: в нём могут находиться ранг и фон.
     */
    public static String detectSuitColor(
            Bitmap bitmap,
            Rect bounds
    ) {
        if (bitmap == null || bitmap.isRecycled() || bounds == null) {
            return "";
        }

        int left = Math.max(0, bounds.left);
        int top = Math.max(0, bounds.top);
        int right = Math.min(bitmap.getWidth(), bounds.right);
        int bottom = Math.min(bitmap.getHeight(), bounds.bottom);

        if (right <= left || bottom <= top) {
            return "";
        }

        int red = 0;
        int blue = 0;
        int green = 0;
        int black = 0;

        int stepX = Math.max(1, (right - left) / 40);
        int stepY = Math.max(1, (bottom - top) / 40);

        for (int y = top; y < bottom; y += stepY) {
            for (int x = left; x < right; x += stepX) {
                int pixel = bitmap.getPixel(x, y);

                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                if (r > 100 && r > g * 1.35f && r > b * 1.25f) {
                    red++;
                } else if (b > 100 && b > r * 1.25f && b > g * 1.10f) {
                    blue++;
                } else if (g > 90 && g > r * 1.15f && g > b * 1.10f) {
                    green++;
                } else if (r < 65 && g < 65 && b < 65) {
                    black++;
                }
            }
        }

        int max = Math.max(
                Math.max(red, blue),
                Math.max(green, black)
        );

        if (max < 3) {
            return "";
        }

        int winners = 0;
        if (red == max) winners++;
        if (blue == max) winners++;
        if (green == max) winners++;
        if (black == max) winners++;

        if (winners != 1) {
            return "";
        }

        if (red == max) return "h";
        if (blue == max) return "d";
        if (green == max) return "c";
        if (black == max) return "s";

        return "";
    }
}
