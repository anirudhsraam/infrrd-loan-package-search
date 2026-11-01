package ai.infrrd.loansearch.api;

import ai.infrrd.loansearch.domain.PageRecord;
import ai.infrrd.loansearch.domain.PackageManifest;
import ai.infrrd.loansearch.domain.PageTextMetadata;
import ai.infrrd.loansearch.service.PackageCatalogService;
import ai.infrrd.loansearch.service.PackageSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PackageController {
    private final PackageCatalogService catalog;

    public PackageController(PackageCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/package")
    public PackageDetails packageDetails() {
        PackageSnapshot snapshot = catalog.snapshot();
        int lowQualityTextPages = (int) snapshot.pages().stream()
                .filter(page -> page.textMetadata().quality() == PageTextMetadata.Quality.LOW).count();
        int ocrPages = (int) snapshot.pages().stream()
                .filter(page -> page.textMetadata().source() == PageTextMetadata.Source.OCR).count();
        return new PackageDetails(snapshot.manifest(), Files.isRegularFile(snapshot.pdfPath()), snapshot.pdfUrl(),
                snapshot.missingTextPages(), snapshot.searchEngine().indexedTokenCount(),
                snapshot.searchEngine().indexedTermCount(), snapshot.manifestReadMs(), snapshot.layoutReadMs(),
                snapshot.pageMappingMs(), snapshot.indexBuildMs(), snapshot.totalBuildMs(),
                lowQualityTextPages, ocrPages);
    }

    @GetMapping("/pages/{pageNumber}")
    public PageDetails page(@PathVariable int pageNumber) {
        PackageSnapshot snapshot = catalog.snapshot();
        PageRecord pageRecord = snapshot.searchEngine().page(pageNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not found"));
        return new PageDetails(pageRecord.pageNumber(), pageRecord.documentId(), pageRecord.documentTitle(),
                pageRecord.documentType(), pageRecord.version(), pageRecord.text(), pageRecord.text().isBlank(),
                pageRecord.textMetadata(), snapshot.pdfUrl());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        PackageSnapshot snapshot = catalog.snapshot();
        return Map.of("status", "UP", "pageCount", snapshot.manifest().pageCount(),
                "documentCount", snapshot.manifest().documents().size(),
                "indexedTokens", snapshot.searchEngine().indexedTokenCount(),
                "indexedTerms", snapshot.searchEngine().indexedTermCount(),
                "missingTextPages", snapshot.missingTextPages(),
                "lowQualityTextPages", snapshot.pages().stream()
                        .filter(page -> page.textMetadata().quality() == PageTextMetadata.Quality.LOW).count(),
                "cachedQueries", snapshot.searchEngine().cachedQueryCount());
    }

    public record PackageDetails(
            PackageManifest manifest,
            boolean pdfAvailable,
            String pdfUrl,
            int missingTextPages,
            int indexedTokens,
            int indexedTerms,
            double manifestReadMs,
            double layoutReadMs,
            double pageMappingMs,
            double indexBuildMs,
            double totalBuildMs,
            int lowQualityTextPages,
            int ocrPages) { }

    public record PageDetails(
            int pageNumber,
            String documentId,
            String documentTitle,
            String documentType,
            String version,
            String text,
            boolean textMissing,
            PageTextMetadata textMetadata,
            String pdfUrl) { }
}
