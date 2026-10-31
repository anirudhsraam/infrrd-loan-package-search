package ai.infrrd.loansearch.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared Unicode-aware tokenization for indexed text and user queries. */
final class TextTokenizer {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");

    private TextTokenizer() { }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) tokens.add(matcher.group());
        return List.copyOf(tokens);
    }
}
