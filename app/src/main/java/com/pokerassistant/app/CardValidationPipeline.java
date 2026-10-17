package com.pokerassistant.app;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Конвейер проверки:
 * текст -> цвет -> расположение -> итоговая валидация.
 *
 * Координаты передаются в системе координат исходного кадра.
 */
public final class CardValidationPipeline {

    public enum Zone {
        BOARD,
        PLAYER,
        UNKNOWN
    }

    /**
     * Границы зон задаются относительно высоты кадра.
     * Пока не откалиброваны по реальному экрану ClubGG,
     * автоматически назначать зону нельзя.
     */
    public static Zone locate(
            Rect bounds,
            int frameWidth,
            int frameHeight,
            float boardTop,
            float boardBottom,
            float playerTop,
            float playerBottom
    ) {
        if (bounds == null
                || frameWidth <= 0
                || frameHeight <= 0
                || bounds.width() <= 0
                || bounds.height() <= 0
                || bounds.left < 0
                || bounds.top < 0
                || bounds.right > frameWidth
                || bounds.bottom > frameHeight) {
            return Zone.UNKNOWN;
        }

        float centerY = (bounds.top + bounds.bottom) / 2f / frameHeight;

        if (centerY >= boardTop && centerY <= boardBottom) {
            return Zone.BOARD;
        }

        if (centerY >= playerTop && centerY <= playerBottom) {
            return Zone.PLAYER;
        }

        return Zone.UNKNOWN;
    }

    public static final class Observation {
        public final String rank;
        public final String suit;
        public final Rect bounds;
        public final float textScore;
        public final float colorScore;
        public final Zone zone;

        public Observation(
                String rank,
                String suit,
                Rect bounds,
                float textScore,
                float colorScore,
                Zone zone
        ) {
            this.rank = rank;
            this.suit = suit;
            this.bounds = bounds == null ? null : new Rect(bounds);
            this.textScore = clamp(textScore);
            this.colorScore = clamp(colorScore);
            this.zone = zone == null ? Zone.UNKNOWN : zone;
        }
    }

    public static final class ValidatedCard {
        public final String code;
        public final Zone zone;
        public final float score;
        public final boolean confirmed;
        public final String reason;

        private ValidatedCard(
                String code,
                Zone zone,
                float score,
                boolean confirmed,
                String reason
        ) {
            this.code = code;
            this.zone = zone;
            this.score = score;
            this.confirmed = confirmed;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return code + " | " + zone
                    + " | " + Math.round(score * 100) + "%"
                    + " | " + reason;
        }
    }

    private CardValidationPipeline() {}

    public static List<ValidatedCard> validate(
            List<Observation> observations,
            int frameWidth,
            int frameHeight
    ) {
        List<ValidatedCard> result = new ArrayList<>();

        if (observations == null || frameWidth <= 0 || frameHeight <= 0) {
            return result;
        }

        Set<String> seen = new HashSet<>();

        for (Observation item : observations) {
            if (item == null) {
                continue;
            }

            // Этап 1: проверяем текстовый ранг.
            String rank = normalizeRank(item.rank);
            if (rank == null) {
                result.add(rejected("??", item.zone, "Ранг не распознан"));
                continue;
            }

            // Этап 2: проверяем код масти.
            String suit = normalizeSuit(item.suit);
            if (suit == null) {
                result.add(rejected(rank + "?", item.zone, "Масть не определена"));
                continue;
            }

            // Этап 3: проверяем положение карты в кадре.
            if (!validBounds(item.bounds, frameWidth, frameHeight)) {
                result.add(rejected(rank + suit, item.zone, "Неверные координаты"));
                continue;
            }

            // Расположение должно быть определено отдельным детектором.
            if (item.zone == Zone.UNKNOWN) {
                result.add(rejected(rank + suit, item.zone, "Зона не определена"));
                continue;
            }

            // Этап 4: объединяем оценки текста и цвета.
            float score = 0.5f * item.textScore
                    + 0.5f * item.colorScore;

            boolean confirmed = score >= 0.80f;

            String code = rank + suit;

            if (!seen.add(item.zone + ":" + code)) {
                continue;
            }

            result.add(new ValidatedCard(
                    code,
                    item.zone,
                    score,
                    confirmed,
                    confirmed
                            ? "Текст, цвет и координаты прошли проверку"
                            : "Недостаточная уверенность"
            ));
        }

        return result;
    }

    private static ValidatedCard rejected(
            String code,
            Zone zone,
            String reason
    ) {
        return new ValidatedCard(code, zone, 0f, false, reason);
    }

    private static boolean validBounds(
            Rect r,
            int width,
            int height
    ) {
        return r != null
                && r.width() > 0
                && r.height() > 0
                && r.left >= 0
                && r.top >= 0
                && r.right <= width
                && r.bottom <= height;
    }

    private static String normalizeRank(String rank) {
        if (rank == null) return null;

        String value = rank.trim().toUpperCase();

        if (value.equals("10")) value = "T";

        return "23456789TJQKA".contains(value)
                && value.length() == 1 ? value : null;
    }

    private static String normalizeSuit(String suit) {
        if (suit == null) return null;

        switch (suit.trim().toLowerCase()) {
            case "h":
            case "♥":
            case "♡":
                return "h";

            case "d":
            case "♦":
            case "♢":
                return "d";

            case "s":
            case "♠":
            case "♤":
                return "s";

            case "c":
            case "♣":
            case "♧":
                return "c";

            default:
                return null;
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
