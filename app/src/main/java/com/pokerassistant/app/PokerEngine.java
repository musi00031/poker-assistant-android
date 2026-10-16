package com.pokerassistant.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class PokerEngine {

    private PokerEngine() {}

    public static final String[] HAND_NAMES = {
        "Старшая карта",
        "Пара",
        "Две пары",
        "Тройка",
        "Стрит",
        "Флеш",
        "Фулл-хаус",
        "Каре",
        "Стрит-флеш"
    };

    public static final class Card {
        public final int rank;
        public final int suit;

        public Card(int rank, int suit) {
            if (rank < 2 || rank > 14 || suit < 0 || suit > 3) {
                throw new IllegalArgumentException("Недопустимая карта");
            }
            this.rank = rank;
            this.suit = suit;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Card)) return false;
            Card other = (Card) obj;
            return rank == other.rank && suit == other.suit;
        }

        @Override
        public int hashCode() {
            return rank * 4 + suit;
        }

        @Override
        public String toString() {
            String ranks = "23456789TJQKA";
            String suits = "shdc";
            return "" + ranks.charAt(rank - 2) + suits.charAt(suit);
        }

        public static Card parse(String text) {
            if (text == null || text.trim().length() != 2) {
                throw new IllegalArgumentException("Формат карты: As, Kh, Qd");
            }

            String s = text.trim();
            String ranks = "23456789TJQKA";
            String suits = "shdc";

            int rank = ranks.indexOf(Character.toUpperCase(s.charAt(0))) + 2;
            int suit = suits.indexOf(Character.toLowerCase(s.charAt(1)));

            if (rank < 2 || suit < 0) {
                throw new IllegalArgumentException("Неизвестная карта: " + text);
            }

            return new Card(rank, suit);
        }
    }

    public static final class Result {
        public final double equity;
        public final int wins;
        public final int ties;
        public final int losses;
        public final int simulations;

        Result(double equity, int wins, int ties, int losses, int simulations) {
            this.equity = equity;
            this.wins = wins;
            this.ties = ties;
            this.losses = losses;
            this.simulations = simulations;
        }
    }

    private static long encode(int category, int... kickers) {
        long score = category;
        for (int i = 0; i < 5; i++) {
            int value = i < kickers.length ? kickers[i] : 0;
            score = score * 15 + value;
        }
        return score;
    }

    private static long evaluateFive(Card[] cards) {
        int[] counts = new int[15];
        boolean flush = true;

        for (int i = 0; i < 5; i++) {
            counts[cards[i].rank]++;
            if (cards[i].suit != cards[0].suit) flush = false;
        }

        List<Integer> distinct = new ArrayList<>();
        for (int r = 14; r >= 2; r--) {
            if (counts[r] > 0) distinct.add(r);
        }

        int straightHigh = 0;

        if (distinct.size() == 5) {
            boolean straight = true;
            for (int i = 1; i < 5; i++) {
                if (distinct.get(i - 1) - distinct.get(i) != 1) {
                    straight = false;
                    break;
                }
            }
            if (straight) straightHigh = distinct.get(0);

            if (distinct.size() == 5 &&
                    distinct.get(0) == 14 &&
                    distinct.get(1) == 5 &&
                    distinct.get(2) == 4 &&
                    distinct.get(3) == 3 &&
                    distinct.get(4) == 2) {
                straightHigh = 5;
            }
        }

        if (flush && straightHigh > 0) {
            return encode(8, straightHigh);
        }

        int four = 0;
        int three1 = 0;
        int three2 = 0;
        List<Integer> pairs = new ArrayList<>();
        List<Integer> singles = new ArrayList<>();

        for (int r = 14; r >= 2; r--) {
            if (counts[r] == 4) four = r;
            else if (counts[r] == 3) {
                if (three1 == 0) three1 = r;
                else three2 = r;
            } else if (counts[r] == 2) {
                pairs.add(r);
            } else if (counts[r] == 1) {
                singles.add(r);
            }
        }

        if (four > 0) {
            int kicker = highestExcept(counts, four);
            return encode(7, four, kicker);
        }

        if (three1 > 0 && (!pairs.isEmpty() || three2 > 0)) {
            int pair = !pairs.isEmpty() ? pairs.get(0) : three2;
            return encode(6, three1, pair);
        }

        if (flush) {
            int[] values = new int[5];
            for (int i = 0; i < 5; i++) values[i] = distinct.get(i);
            return encode(5, values);
        }

        if (straightHigh > 0) {
            return encode(4, straightHigh);
        }

        if (three1 > 0) {
            return encode(3, three1,
                    singles.size() > 0 ? singles.get(0) : 0,
                    singles.size() > 1 ? singles.get(1) : 0);
        }

        if (pairs.size() >= 2) {
            int kicker = singles.isEmpty() ? 0 : singles.get(0);
            return encode(2, pairs.get(0), pairs.get(1), kicker);
        }

        if (pairs.size() == 1) {
            int a = singles.size() > 0 ? singles.get(0) : 0;
            int b = singles.size() > 1 ? singles.get(1) : 0;
            int c = singles.size() > 2 ? singles.get(2) : 0;
            return encode(1, pairs.get(0), a, b, c);
        }

        int[] values = new int[5];
        for (int i = 0; i < 5; i++) values[i] = distinct.get(i);
        return encode(0, values);
    }

    private static int highestExcept(int[] counts, int excluded) {
        for (int r = 14; r >= 2; r--) {
            if (r != excluded && counts[r] > 0) return r;
        }
        return 0;
    }

    private static long evaluateBest(List<Card> cards) {
        if (cards.size() < 5 || cards.size() > 7) {
            throw new IllegalArgumentException("Для оценки нужно 5–7 карт");
        }

        long best = -1;
        int n = cards.size();

        for (int a = 0; a < n - 4; a++) {
            for (int b = a + 1; b < n - 3; b++) {
                for (int c = b + 1; c < n - 2; c++) {
                    for (int d = c + 1; d < n - 1; d++) {
                        for (int e = d + 1; e < n; e++) {
                            Card[] five = {
                                cards.get(a), cards.get(b), cards.get(c),
                                cards.get(d), cards.get(e)
                            };
                            best = Math.max(best, evaluateFive(five));
                        }
                    }
                }
            }
        }

        return best;
    }

    public static String describeHand(List<Card> cards) {
        if (cards.size() < 5) {
            return "Для определения комбинации нужны минимум 5 карт";
        }

        long score = evaluateBest(cards);
        int category = (int) (score / 15L / 15L / 15L / 15L / 15L);

        if (category < 0 || category >= HAND_NAMES.length) {
            return "Не удалось определить комбинацию";
        }

        return HAND_NAMES[category];
    }

    public static Result calculateEquity(
            List<Card> hero,
            List<Card> board,
            int simulations,
            long seed) {

        if (hero == null || hero.size() != 2) {
            throw new IllegalArgumentException("Укажи две карманные карты");
        }

        if (board == null ||
                !(board.size() == 0 || board.size() == 3 ||
                  board.size() == 4 || board.size() == 5)) {
            throw new IllegalArgumentException("Борд: 0, 3, 4 или 5 карт");
        }

        if (simulations < 1) {
            throw new IllegalArgumentException("Число симуляций должно быть больше нуля");
        }

        List<Card> known = new ArrayList<>();
        known.addAll(hero);
        known.addAll(board);

        if (known.size() != known.stream().distinct().count()) {
            throw new IllegalArgumentException("Карты не должны повторяться");
        }

        List<Card> deck = new ArrayList<>();
        for (int rank = 2; rank <= 14; rank++) {
            for (int suit = 0; suit < 4; suit++) {
                Card card = new Card(rank, suit);
                if (!known.contains(card)) deck.add(card);
            }
        }

        Random random = new Random(seed);
        int wins = 0;
        int ties = 0;
        int losses = 0;
        int missing = 5 - board.size();

        for (int i = 0; i < simulations; i++) {
            Collections.shuffle(deck, random);

            List<Card> villain = new ArrayList<>();
            villain.add(deck.get(0));
            villain.add(deck.get(1));

            List<Card> finalBoard = new ArrayList<>(board);
            for (int j = 0; j < missing; j++) {
                finalBoard.add(deck.get(2 + j));
            }

            List<Card> heroCards = new ArrayList<>(hero);
            heroCards.addAll(finalBoard);

            List<Card> villainCards = new ArrayList<>(villain);
            villainCards.addAll(finalBoard);

            long h = evaluateBest(heroCards);
            long v = evaluateBest(villainCards);

            if (h > v) wins++;
            else if (h == v) ties++;
            else losses++;
        }

        double equity = (wins + ties / 2.0) / simulations * 100.0;
        return new Result(equity, wins, ties, losses, simulations);
    }

    public static Result calculateEquity(
            List<Card> hero, List<Card> board, int simulations) {
        return calculateEquity(hero, board, simulations, System.nanoTime());
    }
}
