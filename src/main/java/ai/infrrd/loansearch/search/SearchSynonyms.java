package ai.infrrd.loansearch.search;

import java.util.List;
import java.util.Map;

/** Small, explicit mortgage-review synonym set; alternatives are ORed, never substituted. */
final class SearchSynonyms {
    private static final Map<String, List<List<String>>> ALTERNATIVES = Map.of(
            "paycheck", List.of(List.of("paystub"), List.of("pay", "stub"), List.of("earnings", "statement")),
            "paystub", List.of(List.of("paycheck"), List.of("pay", "stub"), List.of("earnings", "statement")),
            "impound", List.of(List.of("escrow")),
            "income", List.of(List.of("earnings"), List.of("wages"))
    );

    private SearchSynonyms() { }

    static List<List<String>> alternatives(String term) {
        return ALTERNATIVES.getOrDefault(term, List.of());
    }
}
