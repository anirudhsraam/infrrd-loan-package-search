package ai.infrrd.loansearch.service;

import ai.infrrd.loansearch.domain.PageTextMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Reads page-delimited text and verifies optional extraction provenance before using it. */
final class PageTextLoader {
    private static final Logger log = LoggerFactory.getLogger(PageTextLoader.class);
    private static final long MAX_LAYOUT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_PROVENANCE_BYTES = 4L * 1024 * 1024;

    private final ObjectMapper objectMapper;

    PageTextLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<LoadedPageText> readPages(Path layoutPath, int expectedPages, Path pdfPath, String pdfFile) {
        if (Files.notExists(layoutPath)) {
            log.warn("Text layout is missing ({}); continuing with empty page text.", layoutPath);
            return emptyPages(expectedPages);
        }
        if (!Files.isRegularFile(layoutPath)) {
            throw new IllegalStateException("Text layout is not a regular file: " + layoutPath);
        }
        try {
            byte[] layoutBytes = readBounded(layoutPath, MAX_LAYOUT_BYTES,
                    "Extracted layout text exceeds the 64 MiB input limit.");
            String layout = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(layoutBytes)).toString();
            String[] sections = layout.split("\\f", -1);
            if (sections.length == expectedPages + 1 && sections[expectedPages].isEmpty()) {
                sections = java.util.Arrays.copyOf(sections, expectedPages);
            }
            if (sections.length < expectedPages) {
                log.warn("Text file contains {} sections for {} PDF pages; omitted pages remain available without searchable text.",
                        sections.length, expectedPages);
            } else if (sections.length > expectedPages
                    && (sections.length > expectedPages + 1 || !sections[expectedPages].isBlank())) {
                log.warn("Text file has extra sections ({}); ignoring content after physical page {}.", sections.length, expectedPages);
            }

            ExtractionProvenance provenance = readProvenance(layoutPath, layoutBytes, expectedPages, pdfPath, pdfFile);
            List<LoadedPageText> pages = new ArrayList<>(expectedPages);
            for (int page = 0; page < expectedPages; page++) {
                String text = page < sections.length ? sections[page] : "";
                PageTextMetadata.Source source = provenance == null
                        ? (Files.exists(layoutPath) ? PageTextMetadata.Source.LAYOUT : PageTextMetadata.Source.NONE)
                        : provenance.pages().get(page).source();
                PageTextMetadata metadata = PageTextMetadata.assess(text, source);
                if (provenance != null) {
                    PageProvenance expected = provenance.pages().get(page);
                    if (expected.nonWhitespaceCharacters() != metadata.nonWhitespaceCharacters()
                            || expected.quality() != metadata.quality()) {
                        throw new IllegalStateException("Extraction provenance does not match layout text at page " + (page + 1)
                                + "; regenerate the extracted layout and provenance report.");
                    }
                }
                pages.add(new LoadedPageText(text, metadata));
            }
            return List.copyOf(pages);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read text layout or validate its provenance: " + layoutPath, exception);
        }
    }

    private ExtractionProvenance readProvenance(
            Path layoutPath, byte[] layoutBytes, int expectedPages, Path pdfPath, String pdfFile) throws IOException {
        Path provenancePath = layoutPath.resolveSibling(layoutPath.getFileName() + ".provenance.json");
        if (Files.notExists(provenancePath)) return null;
        if (!Files.isRegularFile(provenancePath)) {
            throw new IllegalStateException("Extraction provenance is not a regular file: " + provenancePath);
        }
        byte[] reportBytes = readBounded(provenancePath, MAX_PROVENANCE_BYTES,
                "Extraction provenance exceeds the 4 MiB input limit.");
        ExtractionProvenance provenance = objectMapper.readValue(reportBytes, ExtractionProvenance.class);
        if (provenance.schemaVersion() != 1
                || !sha256(layoutBytes).equals(provenance.layoutSha256())
                || !pdfFile.equals(provenance.sourcePdf())
                || provenance.sourcePdfSha256() == null
                || provenance.pages() == null
                || provenance.pages().size() != expectedPages) {
            throw new IllegalStateException("Extraction provenance does not match the configured PDF/layout; regenerate the extracted layout.");
        }
        if (!Files.isRegularFile(pdfPath)
                || Files.size(pdfPath) != provenance.sourcePdfSizeBytes()
                || !sha256(pdfPath).equals(provenance.sourcePdfSha256())) {
            throw new IllegalStateException("The source PDF changed after text extraction; regenerate the extracted layout and provenance report.");
        }
        for (int page = 0; page < expectedPages; page++) {
            PageProvenance entry = provenance.pages().get(page);
            if (entry == null || entry.pageNumber() != page + 1 || entry.source() == null || entry.quality() == null
                    || entry.source() == PageTextMetadata.Source.LAYOUT
                    || entry.source() == PageTextMetadata.Source.NONE
                    || entry.nonWhitespaceCharacters() < 0) {
                throw new IllegalStateException("Extraction provenance has an invalid page entry at page " + (page + 1) + ".");
            }
        }
        return provenance;
    }

    private static byte[] readBounded(Path path, long limit, String message) throws IOException {
        if (Files.size(path) > limit) throw new IllegalArgumentException(message);
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes((int) limit + 1);
        }
        if (bytes.length > limit) throw new IllegalArgumentException(message);
        return bytes;
    }

    private static List<LoadedPageText> emptyPages(int pageCount) {
        List<LoadedPageText> pages = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            pages.add(new LoadedPageText("", PageTextMetadata.assess("", PageTextMetadata.Source.NONE)));
        }
        return List.copyOf(pages);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path);
                 var digestInput = new java.security.DigestInputStream(input, digest)) {
                digestInput.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    record LoadedPageText(String text, PageTextMetadata metadata) { }

    record ExtractionProvenance(
            int schemaVersion,
            String sourcePdf,
            long sourcePdfSizeBytes,
            long sourcePdfLastModifiedMillis,
            String sourcePdfSha256,
            String layoutSha256,
            String generatedAt,
            List<PageProvenance> pages) { }

    record PageProvenance(
            int pageNumber,
            PageTextMetadata.Source source,
            PageTextMetadata.Quality quality,
            int nonWhitespaceCharacters) { }
}
