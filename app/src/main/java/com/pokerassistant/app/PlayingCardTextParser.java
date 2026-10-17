package com.pokerassistant.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлекает кандидатов в покерные карты из текста OCR.
 * Это предварительный этап: окончательное распознавание мастей
 * по изображению будет выполняться отдельно.
 */
public final class PlayingCardTextParser {

    private PlayingCardTextParser() {}

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?i)(10|[2-9TJQKA])\\s*([♠♤♣♧♥♡♦♢shdc])"
    );

    public static List<PokerEngine.Card> parse(String text) {
        List<PokerEngine.Card> result = new ArrayList<>();

        if (text == null || text.isEmpty()) {
            return result;
        }

        Matcher matcher = CARD_PATTERN.matcher(text);
        Set<String> seen = new HashSet<>();

        while (matcher.find()) {
            String rankText = matcher.group(1).toUpperCase(Locale.ROOT);
            String suitText = matcher.group(2).toLowerCase(Locale.ROOT);

            if ("10".equals(rankText)) {
                rankText = "T";
            }

            char suitChar;
            switch (suitText) {
                case "♠":
                case "♤":
                case "s":
                    suitChar = 's';
                    break;

                case "♥":
                case "♡":
                case "h":
                    suitChar = 'h';
                    break;

                case "♦":
                case "♢":
                case "d":
                    suitChar = 'd';
                    break;

                case "♣":
                case "♧":
                case "c":
                    suitChar = 'c';
                    break;

                default:
                    continue;
            }

            String code = rankText + suitChar;

            // Не добавляем одну карту повторно.
            if (!seen.add(code)) {
                continue;
            }

            try {
                result.add(PokerEngine.Card.parse(code));
            } catch (IllegalArgumentException ignored) {
                // Некорректный кандидат пропускаем.
            }
        }

        return result;
    }

    public static String format(List<PokerEngine.Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return "Кандидаты карт не найдены";
        }

        StringBuilder output = new StringBuilder();

        for (PokerEngine.Card card : cards) {
            if (output.length() > 0) {
                output.append(' ');
            }
            output.append(card.toString());
        }

        return output.toString();
    }
}
