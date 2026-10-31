package ai.infrrd.loansearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PackageCatalogServiceTest {
    @TempDir Path tempDir;

    @Test void manifestEditsAreAppliedOnTheNextSnapshotRequest() throws Exception {
        Path data = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(data.resolve("layout-v1.txt"), "alpha page\fneutral page", StandardCharsets.UTF_8);
        Files.writeString(data.resolve("layout-v2.txt"), "bravo page\fneutral page", StandardCharsets.UTF_8);
        Files.createDirectories(data.resolve("pdf"));
        Path manifest = tempDir.resolve("package.json");
        Files.writeString(manifest, manifestJson("data/layout-v1.txt"), StandardCharsets.UTF_8);

        PackageCatalogService catalog = catalog();
        PackageSnapshot first = catalog.snapshot();
        assertEquals(1, first.searchEngine().search("alpha", 0, 10).totalHits());
        assertEquals(0, first.searchEngine().search("bravo", 0, 10).totalHits());

        Files.writeString(manifest, manifestJson("data/layout-v2.txt"), StandardCharsets.UTF_8);
        PackageSnapshot afterEdit = catalog.snapshot();
        assertNotSame(first, afterEdit);
        assertNotEquals(first.manifestHash(), afterEdit.manifestHash());
        assertEquals(0, afterEdit.searchEngine().search("alpha", 0, 10).totalHits());
        assertEquals(1, afterEdit.searchEngine().search("bravo", 0, 10).totalHits());
    }

    @Test void missingLayoutFileDegradesToEmptyPageText() throws Exception {
        Files.createDirectories(tempDir.resolve("data/pdf"));
        Path manifest = tempDir.resolve("package.json");
        Files.writeString(manifest, manifestJson("data/missing-layout.txt"), StandardCharsets.UTF_8);
        PackageSnapshot snapshot = catalog().snapshot();
        assertEquals(2, snapshot.missingTextPages());
        assertTrue(snapshot.searchEngine().page(1).orElseThrow().text().isEmpty());
    }

    @Test void invalidManifestPageCountIsRejected() throws Exception {
        prepareBasicFiles("data/layout.txt", "alpha page\fbravo page");
        Path manifest = tempDir.resolve("package.json");
        Files.writeString(manifest, manifestJson("data/layout.txt").replace("\"pageCount\":2", "\"pageCount\":9"), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> catalog().snapshot());
    }

    @Test void duplicateDocumentIdsAreRejected() throws Exception {
        prepareBasicFiles("data/layout.txt", "alpha page\fbravo page");
        String duplicate = manifestJson("data/layout.txt").replace(
                "\"documents\":[{\"id\":\"doc-1\"", "\"documents\":[{\"id\":\"doc-1\"");
        duplicate = duplicate.replace("\"pageCount\":2}]", "\"pageCount\":2},{\"id\":\"doc-1\",\"title\":\"Another\",\"type\":\"Test\",\"version\":\"2\",\"startPage\":2,\"endPage\":2,\"pageCount\":1}]");
        Files.writeString(tempDir.resolve("package.json"), duplicate, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> catalog().snapshot());
    }

    @Test void overlappingDocumentPageRangesAreRejected() throws Exception {
        prepareBasicFiles("data/layout.txt", "alpha page\fbravo page");
        String overlapping = manifestJson("data/layout.txt").replace(
                "\"pageCount\":2}]", "\"pageCount\":2},{\"id\":\"doc-2\",\"title\":\"Overlap\",\"type\":\"Test\",\"version\":\"1\",\"startPage\":2,\"endPage\":2,\"pageCount\":1}]");
        Files.writeString(tempDir.resolve("package.json"), overlapping, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> catalog().snapshot());
    }

    @Test void manifestCannotReadOutsideProjectRoot() throws Exception {
        prepareBasicFiles("data/layout.txt", "alpha page\fbravo page");
        Files.writeString(tempDir.resolve("package.json"), manifestJson("../../outside.txt"), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> catalog().snapshot());
    }

    @Test void omittedTextSectionsRemainAsPhysicalPages() throws Exception {
        prepareBasicFiles("data/layout.txt", "only first page text");
        Files.writeString(tempDir.resolve("package.json"), manifestJson("data/layout.txt"), StandardCharsets.UTF_8);
        PackageSnapshot snapshot = catalog().snapshot();
        assertEquals(2, snapshot.pages().size());
        assertEquals("only first page text", snapshot.searchEngine().page(1).orElseThrow().text());
        assertTrue(snapshot.searchEngine().page(2).orElseThrow().text().isBlank());
        assertEquals(1, snapshot.missingTextPages());
    }

    @Test void missingPdfDoesNotPreventTextSearch() throws Exception {
        prepareBasicFiles("data/layout.txt", "alpha page\fbravo page");
        Files.writeString(tempDir.resolve("package.json"), manifestJson("data/layout.txt"), StandardCharsets.UTF_8);
        PackageSnapshot snapshot = catalog().snapshot();
        assertFalse(Files.exists(snapshot.pdfPath()));
        assertEquals(1, snapshot.searchEngine().search("alpha", 0, 10).totalHits());
    }

    @Test void unchangedManifestReusesTheCurrentSnapshot() throws Exception {
        prepareBasicFiles("data/layout.txt", "alpha page\fbravo page");
        Files.writeString(tempDir.resolve("package.json"), manifestJson("data/layout.txt"), StandardCharsets.UTF_8);
        PackageCatalogService catalog = catalog();
        PackageSnapshot first = catalog.snapshot();
        assertSame(first, catalog.snapshot());
    }

    @Test void buildStagesAreMeasuredSeparately() throws Exception {
        prepareBasicFiles("data/layout.txt", "alpha page\fbravo page");
        Files.writeString(tempDir.resolve("package.json"), manifestJson("data/layout.txt"), StandardCharsets.UTF_8);
        PackageSnapshot snapshot = catalog().snapshot();
        assertAll(
                () -> assertTrue(snapshot.manifestReadMs() >= 0),
                () -> assertTrue(snapshot.layoutReadMs() >= 0),
                () -> assertTrue(snapshot.pageMappingMs() >= 0),
                () -> assertTrue(snapshot.indexBuildMs() >= 0),
                () -> assertTrue(snapshot.totalBuildMs() >= snapshot.indexBuildMs())
        );
    }

    private PackageCatalogService catalog() {
        return new PackageCatalogService(new ObjectMapper(), tempDir.toString(), "package.json", "data");
    }

    private void prepareBasicFiles(String relativeLayout, String content) throws Exception {
        Path layout = tempDir.resolve(relativeLayout);
        Files.createDirectories(layout.getParent());
        Files.writeString(layout, content, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("data/pdf"));
    }

    private static String manifestJson(String layoutFile) {
        return """
                {
                  "schemaVersion": 1, "loanId": "test-loan", "borrowerLabel": "Test Borrower",
                  "packageName": "Test Package", "pageCount": 2, "layoutFile": "%s",
                  "pdfFile": "loan-package.pdf",
                  "documents": [{"id":"doc-1", "title":"Test document", "type":"Test",
                    "version":"1", "startPage":1, "endPage":2, "pageCount":2}]
                }
                """.formatted(layoutFile);
    }
}
