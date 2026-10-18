package com.pokerassistant.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлекает карты из текста OCR.
 * Масть не угадывается, если она не распознана.
 */
public final class PlayingCardTextParser {

    private PlayingCardTextParser() {}

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9])" +
            "(10|[2-9TJQKA])\\s*([♠♤♣♧♥♡♦♢SHDC])" +
            "(?![A-Z0-9])"
    );

    public static List<PokerEngine.Card> parse(String text) {
        List<PokerEngine.Card> result = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return result;
        }

        String normalized = text
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replace('０', '0')
                .replace('１', '1')
                .replace("１０", "10")
                .replace('Ａ', 'A')
                .replace('Ｋ', 'K')
                .replace('Ｑ', 'Q')
                .replace('Ｊ', 'J')
                .replace('Ｔ', 'T')
                .replace('Ｓ', 'S')
                .replace('Ｈ', 'H')
                .replace('Ｄ', 'D')
                .replace('Ｃ', 'C')
                .replace('♠', '♠')
                .replace('♤', '♤')
                .replace('♣', '♣')
                .replace('♧', '♧')
                .replace('♥', '♥')
                .replace('♡', '♡')
                .replace('♦', '♦')
                .replace('♢', '♢');

        Set<String> seen = new HashSet<>();
        Matcher matcher = CARD_PATTERN.matcher(normalized);

        while (matcher.find()) {
            addCard(result, seen, matcher.group(1), matcher.group(2));
        }

        return result;
    }

    private static void addCard(
            List<PokerEngine.Card> result,
            Set<String> seen,
            String rankText,
            String suitText
    ) {
        String rank = rankText.toUpperCase(Locale.ROOT);

        if ("10".equals(rank)) {
            rank = "T";
        }

        String suit = suitText.toLowerCase(Locale.ROOT);
        char suitChar;

        switch (suit) {
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
                return;
        }

        String code = rank + suitChar;

        if (!seen.add(code)) {
            return;
        }

        try {
            result.add(PokerEngine.Card.parse(code));
        } catch (IllegalArgumentException ignored) {
            // Некорректный результат OCR пропускается.
        }
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
