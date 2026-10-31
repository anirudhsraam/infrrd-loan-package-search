package ai.infrrd.loansearch.search;

import ai.infrrd.loansearch.domain.PageRecord;

import java.util.*;

/**
 * Positional inverted index with BM25 scoring and bounded query/result caches.
 */
public final class SearchEngine {
    private static final int MAX_PAGES = 20_000;
    private static final int MAX_TOTAL_TOKENS = 2_000_000;
    private static final int MAX_UNIQUE_TERMS = 100_000;
    private static final int MAX_PREFIX_EXPANSIONS = 256, MAX_CACHED_QUERIES = 64, MAX_CACHED_HITS = 5_000;
    private static final int MAX_SNIPPET_CHARS = 220;
    private static final double K1 = 1.2, B = 0.75;

    private final List<PageRecord> pages;
    private final Map<Integer, PageRecord> pagesByNumber;
    private final BitSet searchablePages;
    private final int searchablePageCount;
    private final Map<String, Map<Integer, int[]>> postings = new HashMap<>();
    private final int[] documentLengths;
    private final double averageDocumentLength;
    private final int totalTokens;
    private final Map<String, List<String>> prefixExpansions = new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > 128;
        }
    };
    private final Map<String, CachedQuery> queryCache = new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedQuery> eldest) {
            return size() > MAX_CACHED_QUERIES;
        }
    };

    public SearchEngine(List<PageRecord> sourcePages) {
        Objects.requireNonNull(sourcePages, "sourcePages");
        if (sourcePages.size() > MAX_PAGES)
            throw new IllegalArgumentException("Package exceeds page limit " + MAX_PAGES);
        pages = List.copyOf(sourcePages);
        Map<Integer, PageRecord> pageLookup = new HashMap<>(pages.size());
        for (PageRecord page : pages) {
            if (pageLookup.put(page.pageNumber(), page) != null) {
                throw new IllegalArgumentException("Duplicate physical page number: " + page.pageNumber());
            }
        }
        pagesByNumber = Map.copyOf(pageLookup);
        documentLengths = new int[pages.size()];
        searchablePages = new BitSet(pages.size());
        int tokenCount = 0;
        for (int docId = 0; docId < pages.size(); docId++) {
            List<String> tokens = tokenize(pages.get(docId).text());
            if (!tokens.isEmpty()) searchablePages.set(docId);
            tokenCount += tokens.size();
            if (tokenCount > MAX_TOTAL_TOKENS)
                throw new IllegalArgumentException("Package exceeds token limit " + MAX_TOTAL_TOKENS);
            documentLengths[docId] = tokens.size();
            Map<String, List<Integer>> pagePositions = new HashMap<>();
            for (int position = 0; position < tokens.size(); position++) {
                pagePositions.computeIfAbsent(tokens.get(position), ignored -> new ArrayList<>()).add(position);
            }
            for (Map.Entry<String, List<Integer>> entry : pagePositions.entrySet()) {
                if (!postings.containsKey(entry.getKey()) && postings.size() >= MAX_UNIQUE_TERMS)
                    throw new IllegalArgumentException("Package exceeds unique term limit " + MAX_UNIQUE_TERMS);
                int[] positions = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
                postings.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>()).put(docId, positions);
            }
        }
        totalTokens = tokenCount;
        searchablePageCount = searchablePages.cardinality();
        averageDocumentLength = searchablePageCount == 0 ? 0.0 : (double) totalTokens / searchablePageCount;
    }

    private static List<String> positiveTerms(QueryNode root) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        collectTerms(root, out, false);
        return List.copyOf(out);
    }

    private static void collectTerms(QueryNode node, Set<String> out, boolean negative) {
        if (node instanceof QueryNode.Not not) collectTerms(not.child(), out, !negative);
        else if (node instanceof QueryNode.Term term && !negative) out.add(term.value());
        else if (node instanceof QueryNode.Phrase phrase && !negative) out.addAll(phrase.terms());
        else if (node instanceof QueryNode.Prefix prefix && !negative) out.add(prefix.value());
        else if (node instanceof QueryNode.And and) {
            collectTerms(and.left(), out, negative);
            collectTerms(and.right(), out, negative);
        } else if (node instanceof QueryNode.Or or) {
            collectTerms(or.left(), out, negative);
            collectTerms(or.right(), out, negative);
        }
    }

    private static String snippet(String text, List<String> terms) {
        if (text == null || text.isBlank())
            return "Text extraction is unavailable for this page. Open the PDF page to review the source.";
        if (terms.isEmpty()) return trimSnippet(text, 0);
        String lower = text.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String term : terms) {
            int at = lower.indexOf(term.toLowerCase(Locale.ROOT));
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return trimSnippet(text, Math.max(0, best));
    }

    private static String trimSnippet(String text, int center) {
        int start = Math.max(0, center - MAX_SNIPPET_CHARS / 3), end = Math.min(text.length(), start + MAX_SNIPPET_CHARS);
        if (end < text.length()) {
            int lastSpace = text.lastIndexOf(' ', end);
            if (lastSpace > start) end = lastSpace;
        }
        if (start > 0) {
            int firstSpace = text.indexOf(' ', start);
            if (firstSpace >= 0 && firstSpace < end) start = firstSpace + 1;
        }
        return (start > 0 ? "…" : "") + text.substring(start, end).replaceAll("\\s+", " ").trim() + (end < text.length() ? "…" : "");
    }

    private static List<String> tokenize(String text) {
        return TextTokenizer.tokenize(text);
    }

    private static double ms(long ns) {
        return ns / 1_000_000.0;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    public SearchPage search(String rawQuery, int page, int size) {
        if (rawQuery == null || rawQuery.isBlank()) throw new QuerySyntaxException("Enter a search query.");
        if (rawQuery.length() > 256) throw new QuerySyntaxException("Queries are limited to 256 characters.");
        if (page < 0) throw new QuerySyntaxException("Page must be zero or greater.");
        if (size < 1 || size > 100) throw new QuerySyntaxException("Page size must be between 1 and 100.");
        long offsetLong = (long) page * size;
        if (offsetLong >= MAX_CACHED_HITS && page > 0)
            throw new QuerySyntaxException("Deep pagination is limited to the first " + MAX_CACHED_HITS + " results.");

        String cacheKey = rawQuery.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        long cacheStart = System.nanoTime();
        CachedQuery cached;
        synchronized (queryCache) {
            cached = queryCache.get(cacheKey);
        }
        long cacheLookupNs = System.nanoTime() - cacheStart;
        boolean cacheHit = cached != null;
        long parseNs = 0, evaluationNs = 0, rankingNs = 0;

        if (!cacheHit) {
            long start = System.nanoTime();
            QueryNode root = QueryParser.parse(rawQuery);
            parseNs = System.nanoTime() - start;
            start = System.nanoTime();
            Evaluation evaluation = evaluate(root);
            evaluationNs = System.nanoTime() - start;
            start = System.nanoTime();
            List<ScoredDocument> ranked = new ArrayList<>(evaluation.matches.cardinality());
            for (int docId = evaluation.matches.nextSetBit(0); docId >= 0; docId = evaluation.matches.nextSetBit(docId + 1))
                ranked.add(new ScoredDocument(docId, score(root, docId, evaluation.nodeMatches)));
            ranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed()
                    .thenComparingInt(hit -> pages.get(hit.docId()).pageNumber()));
            int stored = Math.min(ranked.size(), MAX_CACHED_HITS);
            int[] docIds = new int[stored];
            double[] scores = new double[stored];
            for (int i = 0; i < stored; i++) {
                docIds[i] = ranked.get(i).docId();
                scores[i] = ranked.get(i).score();
            }
            cached = new CachedQuery(evaluation.matches.cardinality(), docIds, scores, positiveTerms(root));
            synchronized (queryCache) {
                CachedQuery concurrentlyCached = queryCache.get(cacheKey);
                if (concurrentlyCached == null) queryCache.put(cacheKey, cached);
                else cached = concurrentlyCached;
            }
            rankingNs = System.nanoTime() - start;
        }

        int from = (int) Math.min(offsetLong, cached.docIds().length);
        int to = Math.min(from + size, cached.docIds().length);
        long snippetStart = System.nanoTime();
        List<SearchHit> hits = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            PageRecord record = pages.get(cached.docIds()[i]);
            hits.add(new SearchHit(record.pageNumber(), record.documentId(), record.documentTitle(),
                    record.documentType(), record.version(), round(cached.scores()[i]),
                    snippet(record.text(), cached.terms()), record.text().isBlank()));
        }
        long snippetNs = System.nanoTime() - snippetStart;
        long totalNs = cacheLookupNs + parseNs + evaluationNs + rankingNs + snippetNs;
        return new SearchPage(hits, cached.totalHits(), page, size,
                new SearchTimings(ms(cacheLookupNs), ms(parseNs), ms(evaluationNs), ms(rankingNs), ms(snippetNs), ms(totalNs), cacheHit));
    }

    public int pageCount() {
        return pages.size();
    }

    public int indexedTokenCount() {
        return totalTokens;
    }

    public int indexedTermCount() {
        return postings.size();
    }

    public int cachedQueryCount() {
        synchronized (queryCache) {
            return queryCache.size();
        }
    }

    public Optional<PageRecord> page(int pageNumber) {
        return Optional.ofNullable(pagesByNumber.get(pageNumber));
    }

    public List<PageRecord> pages() {
        return pages;
    }

    private Evaluation evaluate(QueryNode root) {
        IdentityHashMap<QueryNode, BitSet> memo = new IdentityHashMap<>();
        return new Evaluation(evaluateNode(root, memo), memo);
    }

    private BitSet evaluateNode(QueryNode node, IdentityHashMap<QueryNode, BitSet> memo) {
        BitSet prior = memo.get(node);
        if (prior != null) return prior;
        BitSet result;
        if (node instanceof QueryNode.Term term) result = docsForTerm(term.value());
        else if (node instanceof QueryNode.Prefix prefix) {
            result = new BitSet(pages.size());
            for (String term : expand(prefix.value())) result.or(docsForTerm(term));
        } else if (node instanceof QueryNode.Phrase phrase) result = docsForPhrase(phrase.terms());
        else if (node instanceof QueryNode.And and) {
            result = (BitSet) evaluateNode(and.left(), memo).clone();
            result.and(evaluateNode(and.right(), memo));
        } else if (node instanceof QueryNode.Or or) {
            result = (BitSet) evaluateNode(or.left(), memo).clone();
            result.or(evaluateNode(or.right(), memo));
        } else if (node instanceof QueryNode.Not not) {
            // A page without extracted text is unknown, not evidence that a term is absent.
            result = (BitSet) searchablePages.clone();
            result.andNot(evaluateNode(not.child(), memo));
        } else throw new IllegalStateException("Unsupported query node");
        memo.put(node, result);
        return result;
    }

    private BitSet docsForTerm(String term) {
        BitSet result = new BitSet(pages.size());
        Map<Integer, int[]> docs = postings.get(term);
        if (docs != null) docs.keySet().forEach(result::set);
        return result;
    }

    private BitSet docsForPhrase(List<String> terms) {
        BitSet result = new BitSet(pages.size());
        if (terms.isEmpty()) return result;
        Map<Integer, int[]> first = postings.get(terms.get(0));
        if (first == null) return result;
        for (Map.Entry<Integer, int[]> entry : first.entrySet()) {
            int docId = entry.getKey();
            boolean found = false;
            for (int start : entry.getValue()) {
                boolean match = true;
                for (int offset = 1; offset < terms.size(); offset++) {
                    Map<Integer, int[]> next = postings.get(terms.get(offset));
                    int[] positions = next == null ? null : next.get(docId);
                    if (positions == null || Arrays.binarySearch(positions, start + offset) < 0) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    found = true;
                    break;
                }
            }
            if (found) result.set(docId);
        }
        return result;
    }

    private double score(QueryNode node, int docId, IdentityHashMap<QueryNode, BitSet> matches) {
        if (node instanceof QueryNode.Term term) return termScore(term.value(), docId);
        if (node instanceof QueryNode.Prefix prefix) {
            double sum = 0;
            for (String term : expand(prefix.value())) sum += termScore(term, docId);
            return sum;
        }
        if (node instanceof QueryNode.Phrase phrase) {
            double sum = 0;
            for (String term : phrase.terms()) sum += termScore(term, docId);
            return sum + phrase.terms().stream().mapToDouble(this::idf).sum() * 1.5;
        }
        if (node instanceof QueryNode.Not) return 0;
        if (node instanceof QueryNode.And and)
            return childScore(and.left(), docId, matches) + childScore(and.right(), docId, matches);
        if (node instanceof QueryNode.Or or)
            return childScore(or.left(), docId, matches) + childScore(or.right(), docId, matches);
        return 0;
    }

    private double childScore(QueryNode node, int docId, IdentityHashMap<QueryNode, BitSet> matches) {
        BitSet docs = matches.get(node);
        return docs != null && docs.get(docId) ? score(node, docId, matches) : 0;
    }

    private double termScore(String term, int docId) {
        Map<Integer, int[]> docs = postings.get(term);
        int[] positions = docs == null ? null : docs.get(docId);
        if (positions == null || positions.length == 0) return 0;
        double tf = positions.length;
        double norm = 1.0 - B + B * (documentLengths[docId] / Math.max(1.0, averageDocumentLength));
        return idf(term) * (tf * (K1 + 1.0)) / (tf + K1 * norm);
    }

    private double idf(String term) {
        Map<Integer, int[]> docs = postings.get(term);
        int df = docs == null ? 0 : docs.size();
        return Math.log(1.0 + (searchablePageCount - df + 0.5) / (df + 0.5));
    }

    private List<String> expand(String prefix) {
        synchronized (prefixExpansions) {
            return prefixExpansions.computeIfAbsent(prefix, key -> {
                List<String> found = postings.keySet().stream().filter(t -> t.startsWith(key)).sorted()
                        .limit(MAX_PREFIX_EXPANSIONS + 1L).toList();
                if (found.size() > MAX_PREFIX_EXPANSIONS)
                    throw new QuerySyntaxException("Wildcard expands to more than " + MAX_PREFIX_EXPANSIONS + " terms. Narrow the prefix.");
                return found;
            });
        }
    }

    private record Evaluation(BitSet matches, IdentityHashMap<QueryNode, BitSet> nodeMatches) {
    }

    private record ScoredDocument(int docId, double score) {
    }

    private record CachedQuery(int totalHits, int[] docIds, double[] scores, List<String> terms) {
    }

    public record SearchPage(List<SearchHit> hits, int totalHits, int page, int size, SearchTimings timings) {
        public SearchPage {
            hits = List.copyOf(hits);
        }
    }

    public record SearchHit(int pageNumber, String documentId, String documentTitle, String documentType,
                            String version, double score, String snippet, boolean textMissing) {
    }

    public record SearchTimings(double cacheLookupMs, double parseMs, double evaluationMs, double rankingMs,
                                double snippetMs, double totalMs, boolean cacheHit) {
    }
}
