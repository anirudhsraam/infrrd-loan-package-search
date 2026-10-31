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
            @RequestParam(defaultValue = "20") int size) {
        PackageSnapshot snapshot = catalog.snapshot();
        SearchEngine.SearchPage result = snapshot.searchEngine().search(query, page, size);
        return new SearchResponse(query, result.hits(), result.totalHits(), result.page(), result.size(),
                result.timings(), snapshot.manifest().pageCount(), snapshot.missingTextPages(),
                snapshot.manifestReadMs(), snapshot.layoutReadMs(), snapshot.pageMappingMs(),
                snapshot.indexBuildMs(), snapshot.totalBuildMs());
    }

    public record SearchResponse(
            String query,
            List<SearchEngine.SearchHit> hits,
            int totalHits,
            int page,
            int size,
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
