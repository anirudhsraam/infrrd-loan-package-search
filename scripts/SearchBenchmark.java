import ai.infrrd.loansearch.domain.PageRecord;
import ai.infrrd.loansearch.search.SearchEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reproducible benchmark of index construction, first search, and identical cached repeats. */
public final class SearchBenchmark {
    public static void main(String[] args) throws Exception {
        Path layout = Path.of(args.length > 0 ? args[0] : "data/layout.txt");
        long readStart = System.nanoTime();
        String raw = Files.readString(layout, StandardCharsets.UTF_8);
        String[] sections = raw.split("\\f", -1);
        int pages = 560;
        List<PageRecord> records = new ArrayList<>(pages);
        int missing = 0;
        for (int i = 0; i < pages; i++) {
            String text = i < sections.length ? sections[i] : "";
            if (text.isBlank()) missing++;
            records.add(new PageRecord(i + 1, "page-" + (i + 1), "Loan package page " + (i + 1), "Benchmark", "1", text));
        }
        long readNs = System.nanoTime() - readStart;
        long indexStart = System.nanoTime();
        SearchEngine engine = new SearchEngine(records);
        double indexMs = (System.nanoTime() - indexStart) / 1_000_000.0;
        var first = engine.search("\"cash to close\"", 0, 20);
        double coldMs = first.timings().totalMs();
        double[] warm = new double[25];
        for (int i = 0; i < warm.length; i++) warm[i] = engine.search("\"cash to close\"", 0, 20).timings().totalMs();
        Arrays.sort(warm);
        System.out.printf("java=%s pages=%d missingTextPages=%d searchableTokens=%d uniqueTerms=%d%n", System.getProperty("java.version"), pages, missing, engine.indexedTokenCount(), engine.indexedTermCount());
        System.out.printf("layoutLoadAndPageMappingMs=%.3f indexBuildMs=%.3f firstSearchMs=%.3f matchingPages=%d%n",
                readNs/1_000_000.0, indexMs, coldMs, first.totalHits());
        System.out.printf("warmMedianMs=%.3f warmP95Ms=%.3f cacheHit=%s speedup=%.1fx%n",
                warm[warm.length/2], warm[(int)Math.ceil(warm.length*.95)-1],
                engine.search("\"cash to close\"", 0, 20).timings().cacheHit(),
                warm[warm.length/2] == 0 ? 0 : coldMs / warm[warm.length/2]);
    }
}
