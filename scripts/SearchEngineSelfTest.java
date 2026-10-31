import ai.infrrd.loansearch.domain.PageRecord;
import ai.infrrd.loansearch.search.SearchEngine;
import ai.infrrd.loansearch.search.QuerySyntaxException;
import java.util.List;

/** Dependency-free smoke checks for the search core on a plain JDK. */
public final class SearchEngineSelfTest {
    public static void main(String[] args) {
        SearchEngine engine = new SearchEngine(List.of(
                page(1, "cash to close is cash to close loan"),
                page(2, "cash exists and close exists but no exact phrase"),
                page(3, "signing signed signatures"),
                page(4, "cash closing costs"), page(5, "loan escrow"), page(6, "")));
        check(engine.search("cash close", 0, 20).totalHits() == 2, "implicit AND");
        check(engine.search("\"cash to close\"", 0, 20).totalHits() == 1, "exact phrase");
        check(engine.search("cash OR signatures", 0, 20).totalHits() == 4, "OR");
        check(engine.search("cash NOT closing", 0, 20).totalHits() == 2, "NOT");
        check(engine.search("NOT cash", 0, 20).totalHits() == 2, "NOT searchable pages only");
        check(engine.search("NOT NOT cash", 0, 20).totalHits() == 3, "double negation");
        check(engine.search("sign*", 0, 20).totalHits() == 1, "prefix wildcard");
        check(engine.search("cash AND (close OR escrow)", 0, 20).totalHits() == 2, "parentheses");
        check(engine.search("cash OR close AND loan", 0, 20).totalHits() == 3, "operator precedence");
        check(engine.search("ＣＡＳＨ", 0, 20).totalHits() == 3, "Unicode normalization");
        engine.search("cash", 0, 20);
        check(engine.search(" CASH ", 0, 20).timings().cacheHit(), "normalized cache key");
        check(engine.page(6).orElseThrow().text().isEmpty(), "missing page retained");
        expect(QuerySyntaxException.class, () -> engine.search("\"unclosed", 0, 20), "invalid phrase");
        expect(QuerySyntaxException.class, () -> engine.search("s*", 0, 20), "short wildcard prefix");
        expect(QuerySyntaxException.class, () -> engine.search("cash", 500, 10), "deep pagination limit");
        System.out.println("PASS: 15 dependency-free search core checks");
    }

    private static PageRecord page(int number, String text) {
        return new PageRecord(number, "doc-" + number, "Document " + number, "Test", "1", text);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void expect(Class<? extends Throwable> type, Runnable task, String label) {
        try {
            task.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) return;
            throw new AssertionError(label, throwable);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + ": " + label);
    }
}
