package ai.infrrd.loansearch.service;

import ai.infrrd.loansearch.domain.PackageManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Enforces manifest invariants and prevents configured paths escaping their data roots. */
final class PackageManifestValidator {
    private static final int MAX_PAGES = 20_000;
    private static final int MAX_DOCUMENTS = 5_000;

    private PackageManifestValidator() { }

    static void validate(PackageManifest manifest, Path projectRoot, Path dataRoot) {
        if (manifest == null || manifest.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported package manifest schemaVersion.");
        }
        if (isBlank(manifest.loanId()) || isBlank(manifest.borrowerLabel()) || isBlank(manifest.packageName())) {
            throw new IllegalArgumentException("loanId, borrowerLabel, and packageName are required.");
        }
        if (manifest.pageCount() < 1 || manifest.pageCount() > MAX_PAGES) {
            throw new IllegalArgumentException("pageCount must be between 1 and " + MAX_PAGES + ".");
        }
        if (manifest.documents() == null || manifest.documents().size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("documents is required and must not exceed " + MAX_DOCUMENTS + " entries.");
        }

        boolean[] assignedPages = new boolean[manifest.pageCount() + 1];
        Set<String> documentIds = new HashSet<>();
        for (PackageManifest.DocumentEntry document : manifest.documents()) {
            validateDocument(document, manifest.pageCount(), assignedPages, documentIds);
        }
        resolveInside(projectRoot, manifest.layoutFile());
        Path pdfDirectory = resolveInside(dataRoot, "pdf");
        resolveInside(pdfDirectory, manifest.pdfFile());
    }

    static Path resolveInside(Path base, String relativePath) {
        if (isBlank(relativePath)) throw new IllegalArgumentException("A relative data path is required.");
        Path root = base.toAbsolutePath().normalize();
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Data paths must stay inside the configured project/data directory.");
        }
        try {
            if (!canonicalize(candidate).startsWith(canonicalize(root))) {
                throw new IllegalArgumentException("Data paths must not resolve outside the configured project/data directory.");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to validate data path: " + relativePath, exception);
        }
        return candidate;
    }

    private static Path canonicalize(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existingAncestor = absolute;
        while (existingAncestor != null && !Files.exists(existingAncestor)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) return absolute;
        Path realAncestor = existingAncestor.toRealPath();
        return realAncestor.resolve(existingAncestor.relativize(absolute)).normalize();
    }

    private static void validateDocument(
            PackageManifest.DocumentEntry document,
            int totalPages,
            boolean[] assignedPages,
            Set<String> documentIds) {
        if (document == null || isBlank(document.id()) || !documentIds.add(document.id())) {
            throw new IllegalArgumentException("Document IDs must be non-empty and unique.");
        }
        if (isBlank(document.title()) || isBlank(document.type()) || isBlank(document.version())) {
            throw new IllegalArgumentException("Document title, type, and version are required for " + document.id());
        }
        if (document.startPage() < 1 || document.endPage() < document.startPage() || document.endPage() > totalPages) {
            throw new IllegalArgumentException("Invalid page range for document " + document.id());
        }
        if (document.pageCount() != document.endPage() - document.startPage() + 1) {
            throw new IllegalArgumentException("pageCount does not match page range for document " + document.id());
        }
        for (int page = document.startPage(); page <= document.endPage(); page++) {
            if (assignedPages[page]) {
                throw new IllegalArgumentException("Overlapping package document page range at page " + page);
            }
            assignedPages[page] = true;
        }
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
}
