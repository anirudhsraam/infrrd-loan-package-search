package ai.infrrd.loansearch.search;

import ai.infrrd.loansearch.domain.PageRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchQualityEvaluationTest {
    private static final int EVALUATION_DEPTH = 3;

    @Test
    void mortgageReviewerQueriesMeetRelevanceTargetsAtTopThree() {
        SearchEngine engine = new SearchEngine(List.of(
                page(1, "Application", "Uniform residential loan application borrower employment monthly income assets"),
                page(2, "Payroll", "Pay stub earnings statement pay period gross pay deductions net pay"),
                page(3, "Payroll", "Paystub paycheck gross wages earnings"),
                page(4, "Loan Estimate", "Loan Estimate cash to close total estimated closing costs"),
                page(5, "Closing Disclosure", "Closing Disclosure cash to close amount due at closing"),
                page(6, "Bank Statement", "Bank statement checking account balance escrow impound"),
                page(7, "Disclosures", "Escrow insurance payments are disclosed to the borrower"),
                page(8, "Payroll", "Pay stub annual income wage deductions")
        ));
        List<Judgment> judgments = List.of(
                new Judgment("paycheck", Set.of(2, 3, 8)),
                new Judgment("impound", Set.of(6)),
                new Judgment("\"cash to close\"", Set.of(4, 5)),
                new Judgment("income", Set.of(1, 2, 3, 8))
        );

        double meanReciprocalRank = judgments.stream()
                .mapToDouble(judgment -> reciprocalRank(engine, judgment))
                .average().orElseThrow();
        double meanRecallAtThree = judgments.stream()
                .mapToDouble(judgment -> recallAtDepth(engine, judgment))
                .average().orElseThrow();
        double meanNdcgAtThree = judgments.stream()
                .mapToDouble(judgment -> ndcgAtDepth(engine, judgment))
                .average().orElseThrow();

        assertTrue(meanReciprocalRank >= 1.0, "Every evaluation query should have a relevant first result.");
        assertTrue(meanRecallAtThree >= 0.90, "Top-three recall should be at least 0.90.");
        assertTrue(meanNdcgAtThree >= 0.90, "Top-three nDCG should be at least 0.90.");
    }

    @Test
    void documentTypeFilterRestrictsMatchesAndUsesAnIndependentCacheEntry() {
        SearchEngine engine = new SearchEngine(List.of(
                page(1, "Loan Estimate", "Cash to close amount"),
                page(2, "Closing Disclosure", "Cash to close amount"),
                page(3, "Bank Statement", "Cash account balance")
        ));

        assertTrue(engine.search("cash", 0, 20).totalHits() == 3);
        var estimate = engine.search("cash", 0, 20, "Loan Estimate");
        assertTrue(estimate.totalHits() == 1);
        assertTrue(estimate.hits().getFirst().pageNumber() == 1);
        assertTrue(engine.search("cash", 0, 20, "loan estimate").timings().cacheHit());
        assertTrue(engine.search("cash", 0, 20, "Closing Disclosure").hits().getFirst().pageNumber() == 2);
    }

    private static double reciprocalRank(SearchEngine engine, Judgment judgment) {
        List<SearchEngine.SearchHit> hits = engine.search(judgment.query(), 0, EVALUATION_DEPTH).hits();
        for (int index = 0; index < hits.size(); index++) {
            if (judgment.relevantPages().contains(hits.get(index).pageNumber())) return 1.0 / (index + 1);
        }
        return 0.0;
    }

    private static double recallAtDepth(SearchEngine engine, Judgment judgment) {
        long relevant = engine.search(judgment.query(), 0, EVALUATION_DEPTH).hits().stream()
                .filter(hit -> judgment.relevantPages().contains(hit.pageNumber())).count();
        return (double) relevant / judgment.relevantPages().size();
    }

    private static double ndcgAtDepth(SearchEngine engine, Judgment judgment) {
        List<SearchEngine.SearchHit> hits = engine.search(judgment.query(), 0, EVALUATION_DEPTH).hits();
        double dcg = 0.0;
        for (int index = 0; index < hits.size(); index++) {
            if (judgment.relevantPages().contains(hits.get(index).pageNumber())) {
                dcg += 1.0 / (Math.log(index + 2.0) / Math.log(2.0));
            }
        }
        int idealRelevant = Math.min(EVALUATION_DEPTH, judgment.relevantPages().size());
        double idealDcg = 0.0;
        for (int index = 0; index < idealRelevant; index++) {
            idealDcg += 1.0 / (Math.log(index + 2.0) / Math.log(2.0));
        }
        return idealDcg == 0 ? 0 : dcg / idealDcg;
    }

    private static PageRecord page(int number, String type, String text) {
        return new PageRecord(number, "doc-" + number, "Document " + number, type, "1", text);
    }

    private record Judgment(String query, Set<Integer> relevantPages) { }
}
