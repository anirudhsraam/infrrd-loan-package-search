package ai.infrrd.loansearch.search;

import java.util.List;

/** Immutable syntax tree produced by {@link QueryParser}. */
sealed interface QueryNode {
    record Term(String value) implements QueryNode { }
    record Prefix(String value) implements QueryNode { }
    record Phrase(List<String> terms) implements QueryNode {
        public Phrase { terms = List.copyOf(terms); }
    }
    record And(QueryNode left, QueryNode right) implements QueryNode { }
    record Or(QueryNode left, QueryNode right) implements QueryNode { }
    record Not(QueryNode child) implements QueryNode { }
}
