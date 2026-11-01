package ai.infrrd.loansearch.domain;

/** Source attribution and a transparent heuristic quality estimate for one page's extracted text. */
public record PageTextMetadata(Source source, Quality quality, int nonWhitespaceCharacters) {
    public PageTextMetadata {
        if (source == null || quality == null || nonWhitespaceCharacters < 0) {
            throw new IllegalArgumentException("Page text metadata is invalid.");
        }
    }

    public static PageTextMetadata assess(String text, Source source) {
        String value = text == null ? "" : text;
        int characters = 0;
        int searchable = 0;
        int replacementCharacters = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) continue;
            characters++;
            if (Character.isLetterOrDigit(codePoint)) searchable++;
            if (codePoint == 0xfffd) replacementCharacters++;
        }
        Quality quality;
        if (characters == 0) {
            quality = Quality.NO_TEXT;
        } else if (characters < 40
                || (double) searchable / characters < 0.60
                || replacementCharacters > Math.max(1, characters / 100)) {
            quality = Quality.LOW;
        } else {
            quality = Quality.USABLE;
        }
        return new PageTextMetadata(source, quality, characters);
    }

    public enum Source {
        LAYOUT,
        PDF_TEXT,
        OCR,
        NONE
    }

    public enum Quality {
        USABLE,
        LOW,
        NO_TEXT
    }
}
