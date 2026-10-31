package ai.infrrd.loansearch.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses AND/OR/NOT, parentheses, quoted phrases, implicit AND, and suffix wildcards. */
final class QueryParser {
    private final List<Lexeme> tokens;
    private int cursor;

    private QueryParser(String input) {
        tokens = lex(input);
    }

    static QueryNode parse(String input) {
        return new QueryParser(input).parseExpression();
    }

    private QueryNode parseExpression() {
        if (tokens.isEmpty()) throw new QuerySyntaxException("Enter a search term.");
        QueryNode root = parseOr();
        if (cursor != tokens.size()) {
            throw new QuerySyntaxException("Unexpected token: " + tokens.get(cursor).value());
        }
        return root;
    }

    private QueryNode parseOr() {
        QueryNode node = parseAnd();
        while (match(Type.OR)) node = new QueryNode.Or(node, parseAnd());
        return node;
    }

    private QueryNode parseAnd() {
        QueryNode node = parseUnary();
        while (cursor < tokens.size()) {
            if (match(Type.AND)) {
                node = new QueryNode.And(node, parseUnary());
            } else if (match(Type.NOT)) {
                node = new QueryNode.And(node, negate(parseUnary()));
            } else if (startsExpression(peek().type())) {
                node = new QueryNode.And(node, parseUnary());
            } else {
                break;
            }
        }
        return node;
    }

    private QueryNode parseUnary() {
        if (match(Type.NOT)) return negate(parseUnary());
        if (match(Type.LEFT_PAREN)) {
            QueryNode inner = parseOr();
            if (!match(Type.RIGHT_PAREN)) throw new QuerySyntaxException("Missing closing parenthesis.");
            return inner;
        }
        if (cursor >= tokens.size()) throw new QuerySyntaxException("Query ends with an operator.");

        Lexeme token = tokens.get(cursor++);
        if (token.type() == Type.PHRASE) {
            List<String> terms = TextTokenizer.tokenize(token.value());
            if (terms.isEmpty()) throw new QuerySyntaxException("Quoted phrases must contain searchable words.");
            return terms.size() == 1 ? new QueryNode.Term(terms.getFirst()) : new QueryNode.Phrase(terms);
        }
        if (token.type() != Type.WORD) throw new QuerySyntaxException("Unexpected token: " + token.value());

        String value = token.value().toLowerCase(Locale.ROOT);
        if (value.contains("*") && !value.endsWith("*")) {
            throw new QuerySyntaxException("Only suffix wildcards are supported, for example sign*.");
        }
        if (value.endsWith("*")) {
            List<String> prefixes = TextTokenizer.tokenize(value.substring(0, value.length() - 1));
            if (prefixes.size() != 1 || prefixes.getFirst().length() < 2) {
                throw new QuerySyntaxException("Use a wildcard prefix with at least two letters, for example sign*.");
            }
            return new QueryNode.Prefix(prefixes.getFirst());
        }

        List<String> terms = TextTokenizer.tokenize(value);
        if (terms.isEmpty()) throw new QuerySyntaxException("The query contains no searchable words.");
        QueryNode result = new QueryNode.Term(terms.getFirst());
        for (int i = 1; i < terms.size(); i++) result = new QueryNode.And(result, new QueryNode.Term(terms.get(i)));
        return result;
    }

    private boolean match(Type type) {
        if (cursor < tokens.size() && tokens.get(cursor).type() == type) {
            cursor++;
            return true;
        }
        return false;
    }

    private Lexeme peek() { return tokens.get(cursor); }

    private static QueryNode negate(QueryNode node) {
        return node instanceof QueryNode.Not not ? not.child() : new QueryNode.Not(node);
    }

    private static boolean startsExpression(Type type) {
        return type == Type.WORD || type == Type.PHRASE || type == Type.LEFT_PAREN || type == Type.NOT;
    }

    private static List<Lexeme> lex(String input) {
        List<Lexeme> result = new ArrayList<>();
        for (int i = 0; i < input.length();) {
            char current = input.charAt(i);
            if (Character.isWhitespace(current)) { i++; continue; }
            if (current == '(') { result.add(new Lexeme(Type.LEFT_PAREN, "(")); i++; continue; }
            if (current == ')') { result.add(new Lexeme(Type.RIGHT_PAREN, ")")); i++; continue; }
            if (current == '"') {
                StringBuilder phrase = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < input.length()) {
                    char next = input.charAt(i++);
                    if (next == '"') { closed = true; break; }
                    if (next == '\\' && i < input.length()) next = input.charAt(i++);
                    phrase.append(next);
                }
                if (!closed) throw new QuerySyntaxException("Unclosed quoted phrase.");
                result.add(new Lexeme(Type.PHRASE, phrase.toString()));
                continue;
            }
            int start = i;
            while (i < input.length() && !Character.isWhitespace(input.charAt(i))
                    && input.charAt(i) != '(' && input.charAt(i) != ')' && input.charAt(i) != '"') i++;
            String word = input.substring(start, i);
            Type type = switch (word.toUpperCase(Locale.ROOT)) {
                case "AND" -> Type.AND;
                case "OR" -> Type.OR;
                case "NOT" -> Type.NOT;
                default -> Type.WORD;
            };
            result.add(new Lexeme(type, word));
        }
        return List.copyOf(result);
    }

    private enum Type { WORD, PHRASE, AND, OR, NOT, LEFT_PAREN, RIGHT_PAREN }
    private record Lexeme(Type type, String value) { }
}
