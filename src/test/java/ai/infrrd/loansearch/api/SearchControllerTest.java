package ai.infrrd.loansearch.api;

import ai.infrrd.loansearch.domain.PackageManifest;
import ai.infrrd.loansearch.domain.PageRecord;
import ai.infrrd.loansearch.search.SearchEngine;
import ai.infrrd.loansearch.service.PackageCatalogService;
import ai.infrrd.loansearch.service.PackageSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchControllerTest {
    @Test
    void acceptsAConfiguredDocumentTypeAndIncludesItInTheResponse() {
        SearchController controller = controller();

        SearchController.SearchResponse response = controller.search("cash", 0, 20, "Loan Estimate");

        assertEquals("Loan Estimate", response.documentType());
        assertEquals(1, response.totalHits());
        assertEquals(1, response.hits().getFirst().pageNumber());
        assertFalse(response.snapshotRebuilt());
        assertTrue(response.catalogAccessMs() >= 0);
        assertTrue(response.searchHandlingMs() >= 0);
        assertTrue(response.requestTotalMs() >= response.searchHandlingMs());
    }

    @Test
    void rejectsUnknownDocumentTypeFilters() {
        SearchController controller = controller();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.search("cash", 0, 20, "Unknown"));
        assertTrue(error.getMessage().contains("not present"));
    }

    private static SearchController controller() {
        PackageManifest manifest = new PackageManifest(1, "loan", "borrower", "package", 2,
                "data/layout.txt", "loan.pdf", List.of(
                new PackageManifest.DocumentEntry("doc-a", "Estimate", "Loan Estimate", "1", 1, 1, 1),
                new PackageManifest.DocumentEntry("doc-b", "Disclosure", "Closing Disclosure", "1", 2, 2, 1)));
        SearchEngine searchEngine = new SearchEngine(List.of(
                new PageRecord(1, "doc-a", "Estimate", "Loan Estimate", "1", "cash amount"),
                new PageRecord(2, "doc-b", "Disclosure", "Closing Disclosure", "1", "cash amount")));
        PackageSnapshot snapshot = new PackageSnapshot(manifest, searchEngine.pages(), searchEngine,
                Path.of("loan.pdf"), "/package-data/loan.pdf", 0, 0, 0, 0, 0, 0,
                "manifest-hash", "source-fingerprint");
        PackageCatalogService catalog = new PackageCatalogService(
                new ObjectMapper(), ".", "package.json", "data") {
            @Override
            public SnapshotAccess snapshotAccess() {
                return new SnapshotAccess(snapshot, 1.25, false);
            }
        };
        return new SearchController(catalog);
    }
}
