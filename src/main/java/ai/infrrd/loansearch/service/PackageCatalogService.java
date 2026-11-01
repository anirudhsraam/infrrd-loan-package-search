package ai.infrrd.loansearch.service;

import ai.infrrd.loansearch.domain.PackageManifest;
import ai.infrrd.loansearch.domain.PageRecord;
import ai.infrrd.loansearch.domain.PageTextMetadata;
import ai.infrrd.loansearch.search.SearchEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
    private final PageTextLoader pageTextLoader;
    private final Object refreshMonitor = new Object();
    private volatile PackageSnapshot current;

    public PackageCatalogService(
            ObjectMapper objectMapper,
            @Value("${app.project-root:.}") String projectRootValue,
            @Value("${app.package-manifest:package.json}") String manifestValue,
            @Value("${app.data-dir:data}") String dataDirValue) {
        this.objectMapper = objectMapper;
        pageTextLoader = new PageTextLoader(objectMapper);
        projectRoot = Path.of(projectRootValue).toAbsolutePath().normalize();
        manifestPath = PackageManifestValidator.resolveInside(projectRoot, manifestValue);
        dataRoot = PackageManifestValidator.resolveInside(projectRoot, dataDirValue);
    }

    /** Rechecks package source fingerprints on each request and rebuilds only when a source changes. */
    public PackageSnapshot snapshot() {
        long readStarted = System.nanoTime();
        byte[] manifestBytes = readManifestBytes();
        double manifestReadMs = elapsedMs(readStarted);
        String manifestHash = sha256(manifestBytes);

        PackageSnapshot snapshot = current;
        if (snapshot != null && snapshot.manifestHash().equals(manifestHash)
                && snapshot.sourceFingerprint().equals(sourceFingerprint(snapshot.manifest(), manifestHash))) {
            return snapshot;
        }

        synchronized (refreshMonitor) {
            snapshot = current;
            if (snapshot != null && snapshot.manifestHash().equals(manifestHash)
                    && snapshot.sourceFingerprint().equals(sourceFingerprint(snapshot.manifest(), manifestHash))) {
                return snapshot;
            }
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
            try (InputStream input = Files.newInputStream(manifestPath)) {
                byte[] bytes = input.readNBytes((int) MAX_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_MANIFEST_BYTES) {
                    throw new IllegalStateException("package.json exceeds the 4 MiB manifest limit.");
                }
                return bytes;
            }
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
            Path pdfDirectory = PackageManifestValidator.resolveInside(dataRoot, "pdf");
            Path pdfPath = PackageManifestValidator.resolveInside(pdfDirectory, manifest.pdfFile());
            String sourceFingerprint = sourceFingerprint(manifest, hash);
            List<PageTextLoader.LoadedPageText> pageText =
                    pageTextLoader.readPages(layoutPath, manifest.pageCount(), pdfPath, manifest.pdfFile());
            double layoutReadMs = elapsedMs(layoutStarted);

            long mappingStarted = System.nanoTime();
            Map<Integer, PackageManifest.DocumentEntry> documentsByPage = indexDocumentsByPage(manifest);
            List<PageRecord> pages = new ArrayList<>(manifest.pageCount());
            int missingTextPages = appendPageRecords(manifest, pageText, documentsByPage, pages);
            double pageMappingMs = elapsedMs(mappingStarted);

            long indexStarted = System.nanoTime();
            SearchEngine searchEngine = new SearchEngine(pages);
            double indexBuildMs = elapsedMs(indexStarted);

            String pdfUrl = "/package-data/" + pdfDirectory.relativize(pdfPath).toString().replace('\\', '/');

            if (!sourceFingerprint.equals(sourceFingerprint(manifest, hash))) {
                throw new IllegalStateException("Package source files changed while the search index was being built. Retry the request.");
            }
            return new PackageSnapshot(manifest, pages, searchEngine, pdfPath, pdfUrl, missingTextPages,
                    manifestReadMs, layoutReadMs, pageMappingMs, indexBuildMs, elapsedMs(totalStarted), hash,
                    sourceFingerprint);
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
            List<PageTextLoader.LoadedPageText> pageText,
            Map<Integer, PackageManifest.DocumentEntry> documentsByPage,
            List<PageRecord> pages) {
        int missingTextPages = 0;
        for (int pageNumber = 1; pageNumber <= manifest.pageCount(); pageNumber++) {
            PackageManifest.DocumentEntry document = documentsByPage.get(pageNumber);
            PageTextLoader.LoadedPageText loadedText = pageText.get(pageNumber - 1);
            String text = loadedText.text();
            if (text.isBlank()) missingTextPages++;
            pages.add(toPageRecord(pageNumber, document, text, loadedText.metadata()));
        }
        return missingTextPages;
    }

    private PageRecord toPageRecord(
            int pageNumber, PackageManifest.DocumentEntry document, String text,
            PageTextMetadata metadata) {
        if (document == null) {
            return new PageRecord(pageNumber, "unclassified-" + pageNumber,
                    "Unclassified page " + pageNumber, "Other / Scanned", "Unknown", text, metadata);
        }
        return new PageRecord(pageNumber, document.id(), document.title(), document.type(), document.version(), text, metadata);
    }

    private String sourceFingerprint(PackageManifest manifest, String manifestHash) {
        try {
            Path layoutPath = PackageManifestValidator.resolveInside(projectRoot, manifest.layoutFile());
            Path pdfDirectory = PackageManifestValidator.resolveInside(dataRoot, "pdf");
            Path pdfPath = PackageManifestValidator.resolveInside(pdfDirectory, manifest.pdfFile());
            StringBuilder fingerprint = new StringBuilder(manifestHash);
            fingerprint.append('|').append(fileContentHash(layoutPath, 64L * 1024 * 1024));
            Path provenancePath = layoutPath.resolveSibling(layoutPath.getFileName() + ".provenance.json");
            fingerprint.append('|').append(fileContentHash(provenancePath, 4L * 1024 * 1024));
            if (Files.exists(pdfPath)) {
                if (!Files.isRegularFile(pdfPath)) {
                    throw new IllegalStateException("Source PDF is not a regular file: " + pdfPath);
                }
                BasicFileAttributes attributes = Files.readAttributes(pdfPath, BasicFileAttributes.class);
                fingerprint.append('|').append(attributes.size())
                        .append('|').append(attributes.lastModifiedTime().toMillis())
                        .append('|').append(attributes.fileKey());
            } else {
                fingerprint.append("|pdf-missing");
            }
            return sha256(fingerprint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to fingerprint package source files: " + exception.getMessage(), exception);
        }
    }

    private static String fileContentHash(Path path, long maximumBytes) throws IOException {
        if (Files.notExists(path)) return "missing";
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Package source is not a regular file: " + path);
        if (Files.size(path) > maximumBytes) {
            throw new IllegalArgumentException("Package source exceeds its configured size limit: " + path);
        }
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new IllegalArgumentException("Package source exceeds its configured size limit: " + path);
                }
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
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
