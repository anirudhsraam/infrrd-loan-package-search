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
        PackageSnapshot snapshot = catalog.snapshot();
        String filter = documentType == null || documentType.isBlank() ? null : documentType.trim();
        if (filter != null && snapshot.manifest().documents().stream()
                .noneMatch(document -> document.type().equalsIgnoreCase(filter))) {
            throw new IllegalArgumentException("Document type filter is not present in this package.");
        }
        SearchEngine.SearchPage result = snapshot.searchEngine().search(query, page, size, filter);
        return new SearchResponse(query, result.hits(), result.totalHits(), result.page(), result.size(),
                filter, result.timings(), snapshot.manifest().pageCount(), snapshot.missingTextPages(),
                snapshot.manifestReadMs(), snapshot.layoutReadMs(), snapshot.pageMappingMs(),
                snapshot.indexBuildMs(), snapshot.totalBuildMs());
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
            double totalBuildMs) {
        public SearchResponse { hits = List.copyOf(hits); }
    }
}
