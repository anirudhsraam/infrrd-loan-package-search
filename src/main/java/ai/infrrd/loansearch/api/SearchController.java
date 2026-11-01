package ai.infrrd.loansearch.api;

import ai.infrrd.loansearch.search.SearchEngine;
import ai.infrrd.loansearch.service.PackageCatalogService;
import ai.infrrd.loansearch.service.PackageSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final PackageCatalogService catalog;

    public SearchController(PackageCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public SearchResponse search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String documentType) {
        long requestStarted = System.nanoTime();
        PackageCatalogService.SnapshotAccess access = catalog.snapshotAccess();
        PackageSnapshot snapshot = access.snapshot();
        long searchStarted = System.nanoTime();
        String filter = documentType == null || documentType.isBlank() ? null : documentType.trim();
        if (filter != null && snapshot.manifest().documents().stream()
                .noneMatch(document -> document.type().equalsIgnoreCase(filter))) {
            throw new IllegalArgumentException("Document type filter is not present in this package.");
        }
        SearchEngine.SearchPage result = snapshot.searchEngine().search(query, page, size, filter);
        double searchHandlingMs = elapsedMs(searchStarted);
        double requestTotalMs = elapsedMs(requestStarted);
        return new SearchResponse(query, result.hits(), result.totalHits(), result.page(), result.size(),
                filter, result.timings(), snapshot.manifest().pageCount(), snapshot.missingTextPages(),
                snapshot.manifestReadMs(), snapshot.layoutReadMs(), snapshot.pageMappingMs(),
                snapshot.indexBuildMs(), snapshot.totalBuildMs(), access.accessMs(), access.rebuilt(),
                searchHandlingMs, requestTotalMs);
    }

    private static double elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    public record SearchResponse(
            String query,
            List<SearchEngine.SearchHit> hits,
            int totalHits,
            int page,
            int size,
            String documentType,
            SearchEngine.SearchTimings timings,
            int packagePageCount,
            int missingTextPages,
            double manifestReadMs,
            double layoutReadMs,
            double pageMappingMs,
            double indexBuildMs,
            double totalBuildMs,
            double catalogAccessMs,
            boolean snapshotRebuilt,
            double searchHandlingMs,
            double requestTotalMs) {
        public SearchResponse { hits = List.copyOf(hits); }
    }
}
