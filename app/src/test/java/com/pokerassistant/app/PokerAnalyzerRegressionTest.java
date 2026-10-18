package com.pokerassistant.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PokerAnalyzerRegressionTest {

    private static PokerEngine.Card c(String code) {
        return PokerEngine.Card.parse(code);
    }

    private static List<PokerEngine.Card> cards(String... codes) {
        List<PokerEngine.Card> result = new ArrayList<>();
        for (String code : codes) result.add(c(code));
        return result;
    }

    @Test
    public void analyzerIdentifiesAllHoldemStreets() {
        List<PokerEngine.Card> hero = cards("As", "Kh");

        assertEquals("ПРЕФЛОП",
                LiveHandAnalyzer.analyze(hero, Collections.emptyList(), 50).street);
        assertEquals("ФЛОП",
                LiveHandAnalyzer.analyze(hero, cards("Qd", "Jh", "2c"), 50).street);
        assertEquals("ТЁРН",
                LiveHandAnalyzer.analyze(hero, cards("Qd", "Jh", "2c", "9s"), 50).street);
        assertEquals("РИВЕР",
                LiveHandAnalyzer.analyze(hero, cards("Qd", "Jh", "2c", "9s", "3d"), 50).street);
    }

    @Test
    public void analyzerRejectsDuplicateCardsInsteadOfGivingAdvice() {
        LiveHandAnalyzer.Report report = LiveHandAnalyzer.analyze(
                cards("As", "Kh"), cards("As", "2c", "3d"), 75);

        assertTrue(report.hand.contains("повторяющиеся"));
        assertTrue(report.advice.contains("не выдаётся"));
    }

    @Test
    public void analyzerRejectsIncompleteBoardSizes() {
        LiveHandAnalyzer.Report report = LiveHandAnalyzer.analyze(
                cards("As", "Kh"), cards("2c", "3d"), 60);

        assertEquals("Неполная доска", report.street);
        assertTrue(report.advice.contains("остановлен"));
    }

    @Test
    public void analyzerRecognizesFlushDrawAndStraightDraw() {
        LiveHandAnalyzer.Report flushDraw = LiveHandAnalyzer.analyze(
                cards("Ah", "Kh"), cards("Qh", "7h", "2c"), 55);
        assertTrue(flushDraw.draws.contains("Флеш-дро"));

        LiveHandAnalyzer.Report straightDraw = LiveHandAnalyzer.analyze(
                cards("9s", "Ts"), cards("8d", "7c", "2h"), 55);
        assertTrue(straightDraw.draws.contains("стрит-дро")
                || straightDraw.draws.contains("гатшот"));
    }

    @Test
    public void handEvaluatorRecognizesStraightFlush() {
        assertEquals("Стрит-флеш", PokerEngine.describeHand(
                cards("As", "Ks", "Qs", "Js", "Ts", "2d", "3c")));
    }

    @Test
    public void equitySimulationProducesConsistentCounters() {
        PokerEngine.Result result = PokerEngine.calculateEquity(
                cards("As", "Kh"), cards("Qd", "Jh", "2c", "9s", "3d"), 100, 12345L);

        assertEquals(100, result.wins + result.ties + result.losses);
        assertEquals(100, result.simulations);
        assertTrue(result.equity >= 0.0 && result.equity <= 100.0);
    }

    @Test
    public void parserAcceptsModelCardCodes() {
        List<PokerEngine.Card> parsed = PlayingCardTextParser.parse("As");
        assertEquals(1, parsed.size());
        assertEquals("As", parsed.get(0).toString());

        assertEquals("Th", PlayingCardTextParser.parse("10h").get(0).toString());
    }
}
