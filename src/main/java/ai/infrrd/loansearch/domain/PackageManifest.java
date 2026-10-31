package ai.infrrd.loansearch.domain;

import java.util.List;

public record PackageManifest(
        int schemaVersion,
        String loanId,
        String borrowerLabel,
        String packageName,
        int pageCount,
        String layoutFile,
        String pdfFile,
        List<DocumentEntry> documents) {

    public record DocumentEntry(String id, String title, String type, String version,
                                int startPage, int endPage, int pageCount) { }
}
