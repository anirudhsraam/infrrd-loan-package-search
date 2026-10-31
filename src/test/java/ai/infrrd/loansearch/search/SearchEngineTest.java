package ai.infrrd.loansearch.search;

import ai.infrrd.loansearch.domain.PageRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchEngineTest {
    private SearchEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SearchEngine(List.of(
                page(1, "cash to close is cash to close. Loan Estimate details are included."),
                page(2, "Cash appears here and close appears later, but the exact phrase is absent."),
                page(3, "Signing documents require signatures before closing."),
                page(4, "Cash closing costs are itemized."),
                page(5, "Loan underwriting review and escrow analysis."),
                page(6, "")
        ));
    }

    @Test void implicitWordsUseAndSemantics() {
        var result = engine.search("cash close", 0, 20);
        assertEquals(2, result.totalHits());
        assertTrue(result.hits().stream().anyMatch(hit -> hit.pageNumber() == 1));
        assertTrue(result.hits().stream().anyMatch(hit -> hit.pageNumber() == 2));
    }

    @Test void quotedPhraseRequiresAdjacentTermsInOrder() {
        assertEquals(1, engine.search("\"cash to close\"", 0, 20).totalHits());
        assertEquals(0, engine.search("\"cash close\"", 0, 20).totalHits());
    }

    @Test void booleanOrAndNotAreApplied() {
        assertEquals(4, engine.search("cash OR signatures", 0, 20).totalHits());
        assertEquals(2, engine.search("cash AND NOT closing", 0, 20).totalHits());
        assertEquals(2, engine.search("cash NOT closing", 0, 20).totalHits());
        assertEquals(3, engine.search("NOT NOT cash", 0, 20).totalHits());
    }

    @Test void parenthesesRespectBooleanPrecedence() {
        assertEquals(2, engine.search("cash AND (close OR underwriting)", 0, 20).totalHits());
    }

    @Test void suffixWildcardExpandsPrefixTerms() {
        assertEquals(1, engine.search("sign*", 0, 20).totalHits());
    }

    @Test void resultsAreRankedAndPaginationIsStable() {
        var first = engine.search("cash OR loan", 0, 2);
        var second = engine.search("cash OR loan", 1, 2);
        assertEquals(4, first.totalHits());
        assertEquals(2, first.hits().size());
        assertEquals(2, second.hits().size());
        assertNotEquals(first.hits().get(0).pageNumber(), second.hits().get(0).pageNumber());
        for (int i = 1; i < first.hits().size(); i++) {
            assertTrue(first.hits().get(i - 1).score() >= first.hits().get(i).score());
        }
    }

    @Test void queryCacheMakesIdenticalRepeatAHit() {
        engine.search("cash", 0, 10);
        assertTrue(engine.search("cash", 0, 10).timings().cacheHit());
        assertEquals(1, engine.cachedQueryCount());
    }

    @Test void missingTextPageIsRetainedAndMarked() {
        var result = engine.search("loan OR cash OR signatures", 0, 20);
        assertTrue(result.hits().stream().noneMatch(hit -> hit.pageNumber() == 6));
        assertTrue(engine.page(6).orElseThrow().text().isEmpty());
        assertTrue(engine.search("NOT cash", 0, 20).hits().stream()
                .noneMatch(hit -> hit.pageNumber() == 6));
    }

    @Test void malformedQueriesFailClearly() {
        assertThrows(QuerySyntaxException.class, () -> engine.search("\"cash to close", 0, 20));
        assertThrows(QuerySyntaxException.class, () -> engine.search("cash AND", 0, 20));
        assertThrows(QuerySyntaxException.class, () -> engine.search("s*", 0, 20));
        assertThrows(QuerySyntaxException.class, () -> engine.search("ca*h", 0, 20));
    }

    @Test void queryAndPaginationLimitsAreEnforced() {
        assertThrows(QuerySyntaxException.class, () -> engine.search("cash", 0, 101));
        assertThrows(QuerySyntaxException.class, () -> engine.search("cash", 50, 100));
        assertThrows(QuerySyntaxException.class, () -> engine.search("cash", 51, 100));
    }

    @Test void phraseMatchingTreatsPunctuationAsTokenBoundaries() {
        SearchEngine punctuation = new SearchEngine(List.of(page(1, "The closing package says cash-to-close is confirmed.")));
        assertEquals(1, punctuation.search("\"cash to close\"", 0, 10).totalHits());
    }

    @Test void andHasHigherPrecedenceThanOr() {
        var result = engine.search("cash OR close AND loan", 0, 20);
        assertEquals(3, result.totalHits());
        assertTrue(result.hits().stream().anyMatch(hit -> hit.pageNumber() == 2));
        assertFalse(result.hits().stream().anyMatch(hit -> hit.pageNumber() == 5));
    }

    @Test void unicodeCompatibilityCharactersAreNormalized() {
        assertEquals(3, engine.search("ＣＡＳＨ", 0, 20).totalHits());
    }

    @Test void wildcardExpansionLimitIsEnforced() {
        List<PageRecord> pages = new ArrayList<>();
        for (int i = 0; i < 257; i++) pages.add(page(i + 1, "term" + i));
        SearchEngine largeVocabulary = new SearchEngine(pages);
        assertThrows(QuerySyntaxException.class, () -> largeVocabulary.search("te*", 0, 10));
    }

    @Test void cacheUsesNormalizedQueriesAndEvictsOldEntries() {
        engine.search("Cash", 0, 10);
        assertTrue(engine.search("  CASH  ", 0, 10).timings().cacheHit());
        for (int i = 0; i < 70; i++) engine.search("unusedterm" + i, 0, 1);
        assertEquals(64, engine.cachedQueryCount());
    }

    @Test void paginationBeyondAvailableHitsReturnsAnEmptyPage() {
        var result = engine.search("cash", 3, 10);
        assertEquals(3, result.totalHits());
        assertTrue(result.hits().isEmpty());
    }

    @Test void duplicatePhysicalPageNumbersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SearchEngine(List.of(
                page(1, "first"), page(1, "duplicate"))));
    }

    @Test void emptyAndOversizedQueriesAreRejected() {
        assertThrows(QuerySyntaxException.class, () -> engine.search("   ", 0, 10));
        assertThrows(QuerySyntaxException.class, () -> engine.search("x".repeat(257), 0, 10));
    }

    @Test void negationDoesNotTreatMissingTextAsProofOfAbsence() {
        var result = engine.search("NOT cash", 0, 20);
        assertEquals(2, result.totalHits());
        assertTrue(result.hits().stream().noneMatch(hit -> hit.textMissing()));
        assertTrue(engine.page(6).orElseThrow().text().isBlank());
    }

    private static PageRecord page(int number, String text) {
        return new PageRecord(number, "doc-" + number, "Sample document " + number,
                "Test", "1", text);
    }
}
