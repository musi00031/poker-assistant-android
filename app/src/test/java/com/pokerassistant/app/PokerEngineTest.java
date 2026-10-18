package com.pokerassistant.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PokerEngineTest {
    private static List<PokerEngine.Card> cards(String... values) {
        List<PokerEngine.Card> result = new ArrayList<>();
        for (String value : values) result.add(PokerEngine.Card.parse(value));
        return result;
    }

    @Test
    public void recognizesStraightFlush() {
        assertEquals("Стрит-флеш", PokerEngine.describeHand(
                cards("As", "Ks", "Qs", "Js", "Ts", "2d", "3h")));
    }

    @Test
    public void recognizesFourOfAKind() {
        assertEquals("Каре", PokerEngine.describeHand(
                cards("9s", "9h", "9d", "9c", "Ac", "2d", "3h")));
    }

    @Test
    public void recognizesFullHouse() {
        assertEquals("Фулл-хаус", PokerEngine.describeHand(
                cards("As", "Ah", "Ac", "Kd", "Kh", "2c", "3s")));
    }

    @Test
    public void recognizesWheelStraight() {
        assertEquals("Стрит", PokerEngine.describeHand(
                cards("As", "2d", "3c", "4h", "5s", "Kd", "9c")));
    }

    @Test
    public void recognizesFlush() {
        assertEquals("Флеш", PokerEngine.describeHand(
                cards("As", "2s", "3s", "7s", "9s", "Kd", "4c")));
    }

    @Test
    public void rejectsDuplicateCardsInEquityCalculation() {
        assertThrows(IllegalArgumentException.class, () ->
                PokerEngine.calculateEquity(
                        cards("As", "Ah"), cards("As", "7d", "2h"), 10, 7L));
    }

    @Test
    public void equitySimulationIsDeterministicForSameSeed() {
        List<PokerEngine.Card> hero = cards("As", "Ah");
        List<PokerEngine.Card> board = cards("Ac", "7d", "2h");
        PokerEngine.Result first = PokerEngine.calculateEquity(hero, board, 500, 20261019L);
        PokerEngine.Result second = PokerEngine.calculateEquity(hero, board, 500, 20261019L);

        assertEquals(first.wins, second.wins);
        assertEquals(first.ties, second.ties);
        assertEquals(first.losses, second.losses);
        assertEquals(500, first.wins + first.ties + first.losses);
        assertEquals((first.wins + first.ties / 2.0) / 500.0 * 100.0,
                first.equity, 0.000001);
        assertTrue(first.equity >= 0.0 && first.equity <= 100.0);
    }

    @Test
    public void validatesStreetCardCount() {
        assertTrue(LiveHandAnalyzer.analyze(cards("As", "Ah"),
                cards("2c", "3d"), 50.0).street.contains("Неполная"));
    }
}
