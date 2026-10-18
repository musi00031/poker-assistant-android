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
        int[] ranks = new int[15];
        for (PokerEngine.Card c : all) {
            suits[c.suit]++;
            ranks[c.rank]++;
        }

        StringBuilder text = new StringBuilder();
        if (board.size() >= 3) {
            int maxSuit = 0;
            for (PokerEngine.Card c : board) maxSuit = Math.max(maxSuit, suits[c.suit]);
            if (maxSuit >= 5) text.append("Флеш уже собран");
            else if (maxSuit == 4) text.append("Флеш-дро: нужна карта той же масти");
            else if (maxSuit == 3) text.append("Есть потенциал флеша");
            else text.append("Флеш-дро не видно");
        }

        int unique = 0;
        for (int r = 2; r <= 14; r++) if (ranks[r] > 0) unique++;
        boolean openEnded = false, gutshot = false;
        for (int start = 1; start <= 10; start++) {
            int present = 0;
            for (int r = start; r < start + 5; r++) {
                int rank = (r == 1) ? 14 : r;
                if (ranks[rank] > 0) present++;
            }
            if (present == 4) {
                boolean lowMissing = ranks[start == 1 ? 14 : start] == 0;
                boolean highMissing = ranks[start + 4 == 14 ? 14 : start + 4] == 0;
                if (!lowMissing && !highMissing) openEnded = true;
                else gutshot = true;
            }
        }
        if (openEnded) text.append(" · стрит-дро с двумя концами");
        else if (gutshot) text.append(" · гатшот на стрит");
        else if (unique >= 4) text.append(" · возможны стрит-комбинации");

        int pairs = 0, trips = 0, quads = 0;
        for (int count : ranks) {
            if (count == 2) pairs++;
            else if (count == 3) trips++;
            else if (count >= 4) quads++;
        }
        if (quads > 0) text.append(" · каре по известным картам");
        else if (trips > 0 && pairs > 0) text.append(" · фулл-хаус возможен/собран");
        else if (trips > 0) text.append(" · есть тройка по известным картам");
        else if (pairs >= 2) text.append(" · две пары по известным картам");
        else if (pairs == 1) text.append(" · пара по известным картам");
        return text.toString();
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
