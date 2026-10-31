package ai.infrrd.loansearch.service;

import ai.infrrd.loansearch.domain.PackageManifest;
import ai.infrrd.loansearch.domain.PageRecord;
import ai.infrrd.loansearch.search.SearchEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Orchestrates loading and atomic publication of the current loan-package snapshot. */
@Service
public class PackageCatalogService {
    private static final Logger log = LoggerFactory.getLogger(PackageCatalogService.class);
    private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final Path manifestPath;
    private final Path projectRoot;
    private final Path dataRoot;
    private final PageTextLoader pageTextLoader = new PageTextLoader();
    private final Object refreshMonitor = new Object();
    private volatile PackageSnapshot current;

    public PackageCatalogService(
            ObjectMapper objectMapper,
            @Value("${app.project-root:.}") String projectRootValue,
            @Value("${app.package-manifest:package.json}") String manifestValue,
            @Value("${app.data-dir:data}") String dataDirValue) {
        this.objectMapper = objectMapper;
        projectRoot = Path.of(projectRootValue).toAbsolutePath().normalize();
        manifestPath = PackageManifestValidator.resolveInside(projectRoot, manifestValue);
        dataRoot = PackageManifestValidator.resolveInside(projectRoot, dataDirValue);
    }

    /** Re-reads the small manifest on every request; re-indexes only when its content hash changes. */
    public PackageSnapshot snapshot() {
        long readStarted = System.nanoTime();
        byte[] manifestBytes = readManifestBytes();
        double manifestReadMs = elapsedMs(readStarted);
        String manifestHash = sha256(manifestBytes);

        PackageSnapshot snapshot = current;
        if (snapshot != null && snapshot.manifestHash().equals(manifestHash)) return snapshot;

        synchronized (refreshMonitor) {
            snapshot = current;
            if (snapshot != null && snapshot.manifestHash().equals(manifestHash)) return snapshot;
            PackageSnapshot replacement = buildSnapshot(manifestBytes, manifestHash, manifestReadMs);
            current = replacement; // Publish a complete immutable snapshot in one volatile write.
            log.info("Loaded '{}' ({} pages, {} documents, {} pages without text, {} tokens) in {} ms",
                    replacement.manifest().packageName(), replacement.manifest().pageCount(),
                    replacement.manifest().documents().size(), replacement.missingTextPages(),
                    replacement.searchEngine().indexedTokenCount(), replacement.totalBuildMs());
            return replacement;
        }
    }

    private byte[] readManifestBytes() {
        try {
            if (!Files.isRegularFile(manifestPath)) {
                throw new IllegalStateException("package.json not found in the configured project root.");
            }
            if (Files.size(manifestPath) > MAX_MANIFEST_BYTES) {
                throw new IllegalStateException("package.json exceeds the 4 MiB manifest limit.");
            }
            return Files.readAllBytes(manifestPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read package.json: " + exception.getMessage(), exception);
        }
    }

    private PackageSnapshot buildSnapshot(byte[] manifestBytes, String hash, double manifestReadMs) {
        long totalStarted = System.nanoTime();
        try {
            PackageManifest manifest = objectMapper.readValue(manifestBytes, PackageManifest.class);
            PackageManifestValidator.validate(manifest, projectRoot, dataRoot);

            long layoutStarted = System.nanoTime();
            Path layoutPath = PackageManifestValidator.resolveInside(projectRoot, manifest.layoutFile());
            List<String> pageText = pageTextLoader.readPages(layoutPath, manifest.pageCount());
            double layoutReadMs = elapsedMs(layoutStarted);

            long mappingStarted = System.nanoTime();
            Map<Integer, PackageManifest.DocumentEntry> documentsByPage = indexDocumentsByPage(manifest);
            List<PageRecord> pages = new ArrayList<>(manifest.pageCount());
            int missingTextPages = appendPageRecords(manifest, pageText, documentsByPage, pages);
            double pageMappingMs = elapsedMs(mappingStarted);

            long indexStarted = System.nanoTime();
            SearchEngine searchEngine = new SearchEngine(pages);
            double indexBuildMs = elapsedMs(indexStarted);

            Path pdfDirectory = PackageManifestValidator.resolveInside(dataRoot, "pdf");
            Path pdfPath = PackageManifestValidator.resolveInside(pdfDirectory, manifest.pdfFile());
            String pdfUrl = "/package-data/" + pdfDirectory.relativize(pdfPath).toString().replace('\\', '/');

            return new PackageSnapshot(manifest, pages, searchEngine, pdfPath, pdfUrl, missingTextPages,
                    manifestReadMs, layoutReadMs, pageMappingMs, indexBuildMs, elapsedMs(totalStarted), hash);
        } catch (IOException exception) {
            throw new IllegalArgumentException("package.json is invalid or unreadable: " + exception.getMessage(), exception);
        }
    }

    private Map<Integer, PackageManifest.DocumentEntry> indexDocumentsByPage(PackageManifest manifest) {
        Map<Integer, PackageManifest.DocumentEntry> documentsByPage = new HashMap<>(manifest.pageCount());
        for (PackageManifest.DocumentEntry document : manifest.documents()) {
            for (int page = document.startPage(); page <= document.endPage(); page++) {
                documentsByPage.put(page, document);
            }
        }
        return documentsByPage;
    }

    private int appendPageRecords(
            PackageManifest manifest,
            List<String> pageText,
            Map<Integer, PackageManifest.DocumentEntry> documentsByPage,
            List<PageRecord> pages) {
        int missingTextPages = 0;
        for (int pageNumber = 1; pageNumber <= manifest.pageCount(); pageNumber++) {
            PackageManifest.DocumentEntry document = documentsByPage.get(pageNumber);
            String text = pageText.get(pageNumber - 1);
            if (text.isBlank()) missingTextPages++;
            pages.add(toPageRecord(pageNumber, document, text));
        }
        return missingTextPages;
    }

    private PageRecord toPageRecord(int pageNumber, PackageManifest.DocumentEntry document, String text) {
        if (document == null) {
            return new PageRecord(pageNumber, "unclassified-" + pageNumber,
                    "Unclassified page " + pageNumber, "Other / Scanned", "Unknown", text);
        }
        return new PageRecord(pageNumber, document.id(), document.title(), document.type(), document.version(), text);
    }

    private static double elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
