package ai.infrrd.loansearch.service;

import ai.infrrd.loansearch.domain.PackageManifest;
import ai.infrrd.loansearch.domain.PageRecord;
import ai.infrrd.loansearch.search.SearchEngine;

import java.nio.file.Path;
import java.util.List;

/** Immutable, atomically published view of one validated package-manifest revision. */
public record PackageSnapshot(
        PackageManifest manifest,
        List<PageRecord> pages,
        SearchEngine searchEngine,
        Path pdfPath,
        String pdfUrl,
        int missingTextPages,
        double manifestReadMs,
        double layoutReadMs,
        double pageMappingMs,
        double indexBuildMs,
        double totalBuildMs,
        String manifestHash) {

    public PackageSnapshot {
        pages = List.copyOf(pages);
    }
}
