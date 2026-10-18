package com.pokerassistant.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Live, explainable Hold'em street analysis. Equity is explicitly against a
 * random opponent hand; this is guidance, not a guaranteed action or a range solver.
 */
public final class LiveHandAnalyzer {
    private LiveHandAnalyzer() {}

    public static final class Report {
        public final String street;
        public final String hand;
        public final String draws;
        public final String advice;

        Report(String street, String hand, String draws, String advice) {
            this.street = street;
            this.hand = hand;
            this.draws = draws;
            this.advice = advice;
        }

        public String toString() {
            return street + "\n" + hand + "\n" + draws + "\n" + advice;
        }
    }

    public static Report analyze(List<PokerEngine.Card> hero,
                                 List<PokerEngine.Card> board,
                                 double equityVsRandom) {
        if (hero == null || hero.size() != 2 || board == null) {
            return new Report("Анализ недоступен", "Проверь свои карты",
                    "Общие карты не проверены", "Рекомендация пока не рассчитана.");
        }

        String street;
        switch (board.size()) {
            case 0: street = "ПРЕФЛОП"; break;
            case 3: street = "ФЛОП"; break;
            case 4: street = "ТЁРН"; break;
            case 5: street = "РИВЕР"; break;
            default:
                return new Report("Неполная доска", "Нужно 0, 3, 4 или 5 общих карт",
                        "Заполни карты по порядку", "Расчёт остановлен, чтобы не дать ложный совет.");
        }

        List<PokerEngine.Card> known = new ArrayList<>(hero);
        known.addAll(board);
        Set<PokerEngine.Card> unique = new HashSet<>(known);
        if (unique.size() != known.size()) {
            return new Report(street, "Обнаружены повторяющиеся карты",
                    "Проверь распознавание или выбор карт",
                    "Совет не выдаётся, пока карты не исправлены.");
        }

        String handText = board.size() < 3
                ? "Комбинация: ещё не сформирована"
                : "Комбинация: " + PokerEngine.describeHand(known);
        String draws = board.isEmpty()
                ? preflopTexture(hero)
                : boardTexture(hero, board);
        String advice = adviceFor(street, equityVsRandom, board);
        return new Report(street, handText, draws, advice);
    }

    private static String preflopTexture(List<PokerEngine.Card> hero) {
        PokerEngine.Card a = hero.get(0), b = hero.get(1);
        int high = Math.max(a.rank, b.rank);
        int low = Math.min(a.rank, b.rank);
        boolean pair = a.rank == b.rank;
        boolean suited = a.suit == b.suit;
        int gap = high - low;
        String quality;
        if (pair && high >= 11) quality = "Сильная карманная пара";
        else if (pair) quality = "Карманная пара";
        else if (high == 14 && low >= 10) quality = "Сильные разномастные/одномастные старшие карты";
        else if (suited && gap <= 2 && high >= 9) quality = "Одномастные связанные карты";
        else if (high >= 12 && low >= 10) quality = "Две высокие карты";
        else quality = "Сила руки зависит от позиции и действий до тебя";
        return quality + (suited ? " · одномастные" : " · разномастные");
    }

    private static String boardTexture(List<PokerEngine.Card> hero,
                                       List<PokerEngine.Card> board) {
        List<PokerEngine.Card> all = new ArrayList<>(hero);
        all.addAll(board);
        int[] suits = new int[4];
        int[] rankCounts = new int[15];
        Set<Integer> ranks = new HashSet<>();
        for (PokerEngine.Card card : all) {
            suits[card.suit]++;
            rankCounts[card.rank]++;
            ranks.add(card.rank);
        }

        StringBuilder text = new StringBuilder();
        int maxSuit = 0;
        for (int count : suits) maxSuit = Math.max(maxSuit, count);

        if (maxSuit >= 5) {
            text.append("Флеш уже собран");
        } else if (board.size() < 5 && maxSuit == 4) {
            int outs = 9;
            double chance = chanceToHit(outs, all.size(), 5 - board.size());
            text.append("Флеш-дро: 9 карт одной масти, около ")
                    .append(Math.round(chance)).append("% до ривера");
        } else if (board.size() == 3 && maxSuit == 3) {
            text.append("Бэкдорное флеш-дро: нужны две карты одной масти");
        } else if (board.size() < 5) {
            text.append("Флеш-дро не видно");
        }

        boolean straightMade = hasStraight(ranks);
        if (straightMade) {
            append(text, "стрит уже собран");
        } else if (board.size() < 5) {
            Set<Integer> completionRanks = new HashSet<>();
            for (int rank = 2; rank <= 14; rank++) {
                if (ranks.contains(rank)) continue;
                Set<Integer> trial = new HashSet<>(ranks);
                trial.add(rank);
                if (hasStraight(trial)) completionRanks.add(rank);
            }
            if (!completionRanks.isEmpty()) {
                int outs = Math.min(47, completionRanks.size() * 4);
                double chance = chanceToHit(outs, all.size(), 5 - board.size());
                if (completionRanks.size() == 1) {
                    append(text, "гатшот: 4 ранговые карты могут закрыть стрит");
                } else if (completionRanks.size() == 2) {
                    append(text, "стрит-дро с двумя вариантами закрытия: 8 ранговых карт");
                } else {
                    append(text, "стрит-дро: " + outs + " карт по рангам могут закрыть стрит");
                }
                append(text, "примерный шанс закрыть стрит до ривера: "
                        + Math.round(chance) + "%");
            }
        }

        int pairs = 0, trips = 0, quads = 0;
        for (int count : rankCounts) {
            if (count == 2) pairs++;
            else if (count == 3) trips++;
            else if (count >= 4) quads++;
        }
        if (quads > 0) append(text, "каре по известным картам");
        else if (trips > 0 && pairs > 0) append(text, "фулл-хаус по известным картам");
        else if (trips > 0) append(text, "тройка по известным картам");
        else if (pairs >= 2) append(text, "две пары по известным картам");
        else if (pairs == 1) append(text, "пара по известным картам");

        return text.length() == 0 ? "Дро не обнаружены" : text.toString();
    }

    private static void append(StringBuilder text, String phrase) {
        if (text.length() > 0) text.append(" · ");
        text.append(phrase);
    }

    private static boolean hasStraight(Set<Integer> ranks) {
        // Ace is both the highest rank and the low card in A-2-3-4-5.
        for (int high = 5; high <= 14; high++) {
            boolean complete = true;
            for (int rank = high - 4; rank <= high; rank++) {
                int actual = rank == 1 ? 14 : rank;
                if (!ranks.contains(actual)) {
                    complete = false;
                    break;
                }
            }
            if (complete) return true;
        }
        return false;
    }

    private static double chanceToHit(int outs, int knownCards, int futureCards) {
        int unseen = 52 - knownCards;
        if (outs <= 0 || unseen <= 0 || futureCards <= 0) return 0.0;
        outs = Math.min(outs, unseen);
        double miss = 1.0;
        int draws = Math.min(futureCards, unseen);
        for (int i = 0; i < draws; i++) {
            int remainingCards = unseen - i;
            int remainingMisses = remainingCards - outs;
            if (remainingMisses <= 0) return 100.0;
            miss *= remainingMisses / (double) remainingCards;
        }
        return (1.0 - miss) * 100.0;
    }

    private static String adviceFor(String street, double equity, List<PokerEngine.Card> board) {
        if (Double.isNaN(equity) || equity < 0 || equity > 100) {
            return "Совет недоступен: проверь карты и пересчитай.";
        }
        String strength;
        if (equity >= 70) strength = "Высокое эквити против случайной руки";
        else if (equity >= 55) strength = "Преимущество по эквити против случайной руки";
        else if (equity >= 40) strength = "Среднее эквити против случайной руки";
        else strength = "Низкое эквити против случайной руки";

        String action;
        if (street.equals("ПРЕФЛОП")) {
            action = equity >= 65
                    ? "Рассмотри рейз/ставку; учитывай позицию и диапазон соперника."
                    : equity >= 50
                    ? "Играй осторожно: решение зависит от позиции, размера рейза и стека."
                    : "Не вкладывайся автоматически: проверь позицию, цену входа и действия соперников.";
        } else if (street.equals("ФЛОП")) {
            action = equity >= 65
                    ? "Можно рассмотреть ставку на вэлью; оцени текстуру флопа и число соперников."
                    : equity >= 48
                    ? "Чаще продолжай с планом: сравни цену колла, дро и агрессию соперника."
                    : "Без сильного дро или хорошей цены чаще выбирай чек/фолд; не раздувай банк автоматически.";
        } else if (street.equals("ТЁРН")) {
            action = equity >= 68
                    ? "Рассмотри ставку на вэлью/защиту, если хуже руки продолжают платить."
                    : equity >= 50
                    ? "Продолжай только с понятной целью; учитывай размер банка и цену колла."
                    : "Чаще контролируй банк или фолди против крупной ставки без достаточных шансов.";
        } else {
            action = equity >= 65
                    ? "Рассмотри вэлью-ставку, если соперник способен оплатить худшей рукой."
                    : equity >= 45
                    ? "На ривере сравни цену колла с шансами банка; не коллируй только ради любопытства."
                    : "Без блеф-кетчера и достаточных шансов банка чаще фолд против ставки.";
        }
        return strength + " (" + String.format(java.util.Locale.US, "%.1f", equity) + "%). "
                + action + " Это ориентир против случайной руки, не точный расчёт диапазона.";
    }
}
