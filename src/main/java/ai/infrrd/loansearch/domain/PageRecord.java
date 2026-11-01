package ai.infrrd.loansearch.domain;

/** One physical PDF page and the document metadata assigned to it by package.json. */
public record PageRecord(int pageNumber, String documentId, String documentTitle,
                         String documentType, String version, String text, PageTextMetadata textMetadata) {
    public PageRecord {
        if (pageNumber < 1) throw new IllegalArgumentException("pageNumber must be positive");
        documentId = safe(documentId, "unclassified");
        documentTitle = safe(documentTitle, "Unclassified page");
        documentType = safe(documentType, "Other / Scanned");
        version = safe(version, "Unknown");
        text = text == null ? "" : text;
        textMetadata = textMetadata == null
                ? PageTextMetadata.assess(text, text.isBlank() ? PageTextMetadata.Source.NONE : PageTextMetadata.Source.LAYOUT)
                : textMetadata;
    }

    public PageRecord(int pageNumber, String documentId, String documentTitle,
                      String documentType, String version, String text) {
        this(pageNumber, documentId, documentTitle, documentType, version, text, null);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
