package ai.infrrd.loansearch.service;

import ai.infrrd.loansearch.domain.PageTextMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Offline PDF text extraction with optional, explicitly enabled Tesseract OCR. */
@Service
public class PdfTextExtractor {
    private static final long MAX_LAYOUT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_OCR_OUTPUT_BYTES = 1024L * 1024;
    private static final long MAX_OCR_PIXELS = 20_000_000L;
    private static final Pattern OCR_LANGUAGE = Pattern.compile("[A-Za-z0-9_]+");
    private final ObjectMapper objectMapper;

    public PdfTextExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ExtractionResult extract(
            Path pdfPath,
            String relativePdfPath,
            Path layoutPath,
            int expectedPages,
            boolean ocrEnabled,
            String tesseractExecutable,
            String ocrLanguage,
            int ocrDpi,
            int ocrTimeoutSeconds) throws IOException {
        if (!Files.isRegularFile(pdfPath)) throw new IllegalStateException("Source PDF is not a regular file: " + pdfPath);
        if (expectedPages < 1) throw new IllegalArgumentException("Expected page count must be positive.");
        if (ocrEnabled && (tesseractExecutable == null || tesseractExecutable.isBlank()
                || ocrLanguage == null || !OCR_LANGUAGE.matcher(ocrLanguage).matches()
                || ocrDpi < 72 || ocrDpi > 300
                || ocrTimeoutSeconds < 1 || ocrTimeoutSeconds > 300)) {
            throw new IllegalArgumentException("OCR settings are invalid. Use a configured executable, language code, 72-300 DPI, and 1-300 second timeout.");
        }

        Path provenancePath = layoutPath.resolveSibling(layoutPath.getFileName() + ".provenance.json");
        if (layoutPath.toAbsolutePath().normalize().equals(pdfPath.toAbsolutePath().normalize())
                || provenancePath.toAbsolutePath().normalize().equals(pdfPath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Extraction output must not overwrite the source PDF.");
        }
        Files.createDirectories(layoutPath.toAbsolutePath().normalize().getParent());
        Path tempDirectory = Files.createTempDirectory(layoutPath.toAbsolutePath().normalize().getParent(), ".pdf-extraction-");
        Path tempLayout = tempDirectory.resolve("layout.txt");
        Path tempReport = tempDirectory.resolve("layout.provenance.json");
        try {
            Path absolutePdfPath = pdfPath.toAbsolutePath().normalize();
            BasicFileAttributes initialPdfAttributes = Files.readAttributes(absolutePdfPath, BasicFileAttributes.class);
            String initialPdfHash = sha256(absolutePdfPath);
            List<String> sections;
            List<PageTextMetadata.Source> sources = new ArrayList<>(expectedPages);
            try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
                if (document.getNumberOfPages() != expectedPages) {
                    throw new IllegalArgumentException("The source PDF has " + document.getNumberOfPages()
                            + " pages, but the manifest declares " + expectedPages + ".");
                }
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setPageStart("");
                stripper.setPageEnd("\f");
                String extracted = stripper.getText(document);
                sections = splitPages(extracted, expectedPages);
                for (int page = 0; page < expectedPages; page++) {
                    sources.add(PageTextMetadata.Source.PDF_TEXT);
                }
                if (ocrEnabled) {
                    PDFRenderer renderer = new PDFRenderer(document);
                    for (int page = 0; page < sections.size(); page++) {
                        PageTextMetadata metadata = PageTextMetadata.assess(sections.get(page), PageTextMetadata.Source.PDF_TEXT);
                        if (metadata.quality() != PageTextMetadata.Quality.USABLE) {
                            checkRenderBounds(document, page, ocrDpi);
                            sections.set(page, runOcr(renderer, page, tempDirectory,
                                    tesseractExecutable, ocrLanguage, ocrDpi, ocrTimeoutSeconds));
                            sources.set(page, PageTextMetadata.Source.OCR);
                        }
                    }
                }
            }

            List<PageTextLoader.PageProvenance> pageReports = new ArrayList<>(expectedPages);
            StringBuilder layout = new StringBuilder();
            for (int page = 0; page < sections.size(); page++) {
                String text = sections.get(page);
                PageTextMetadata.Source source = sources.get(page);
                PageTextMetadata metadata = PageTextMetadata.assess(text, source);
                pageReports.add(new PageTextLoader.PageProvenance(
                        page + 1, source, metadata.quality(), metadata.nonWhitespaceCharacters()));
                layout.append(text);
                if (page + 1 < sections.size()) layout.append('\f');
                if (layout.length() > MAX_LAYOUT_BYTES) {
                    throw new IllegalArgumentException("Extracted layout text exceeds the 64 MiB output limit.");
                }
            }

            byte[] layoutBytes = layout.toString().getBytes(StandardCharsets.UTF_8);
            if (layoutBytes.length > MAX_LAYOUT_BYTES) {
                throw new IllegalArgumentException("Extracted layout text exceeds the 64 MiB output limit.");
            }
            Files.write(tempLayout, layoutBytes);
            BasicFileAttributes finalPdfAttributes = Files.readAttributes(absolutePdfPath, BasicFileAttributes.class);
            String finalPdfHash = sha256(absolutePdfPath);
            if (initialPdfAttributes.size() != finalPdfAttributes.size()
                    || initialPdfAttributes.lastModifiedTime().toMillis() != finalPdfAttributes.lastModifiedTime().toMillis()
                    || !java.util.Objects.equals(initialPdfAttributes.fileKey(), finalPdfAttributes.fileKey())
                    || !initialPdfHash.equals(finalPdfHash)) {
                throw new IllegalStateException("The source PDF changed during extraction. No extracted output was published.");
            }
            PageTextLoader.ExtractionProvenance provenance = new PageTextLoader.ExtractionProvenance(
                    1,
                    relativePdfPath,
                    finalPdfAttributes.size(),
                    finalPdfAttributes.lastModifiedTime().toMillis(),
                    finalPdfHash,
                    sha256(layoutBytes),
                    Instant.now().toString(),
                    List.copyOf(pageReports));
            Files.write(tempReport, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(provenance));

            moveReplacing(tempLayout, layoutPath);
            moveReplacing(tempReport, provenancePath);
            long usablePages = pageReports.stream()
                    .filter(page -> page.quality() == PageTextMetadata.Quality.USABLE)
                    .count();
            long ocrPages = pageReports.stream()
                    .filter(page -> page.source() == PageTextMetadata.Source.OCR)
                    .count();
            return new ExtractionResult(expectedPages, (int) usablePages, (int) ocrPages,
                    layoutPath, provenancePath);
        } finally {
            deleteTemporaryDirectory(tempDirectory);
        }
    }

    private static List<String> splitPages(String extracted, int expectedPages) {
        String[] parts = extracted.split("\\f", -1);
        if (parts.length == expectedPages + 1 && parts[expectedPages].isEmpty()) {
            parts = java.util.Arrays.copyOf(parts, expectedPages);
        }
        if (parts.length != expectedPages) {
            throw new IllegalStateException("PDF text extraction produced " + parts.length
                    + " page sections for a " + expectedPages + "-page PDF.");
        }
        return new ArrayList<>(java.util.Arrays.asList(parts));
    }

    private static void checkRenderBounds(PDDocument document, int pageIndex, int dpi) {
        var box = document.getPage(pageIndex).getMediaBox();
        long width = (long) Math.ceil(box.getWidth() * dpi / 72.0);
        long height = (long) Math.ceil(box.getHeight() * dpi / 72.0);
        if (width <= 0 || height <= 0 || width > MAX_OCR_PIXELS || height > MAX_OCR_PIXELS
                || width * height > MAX_OCR_PIXELS) {
            throw new IllegalArgumentException("Page " + (pageIndex + 1) + " is too large to render safely for OCR.");
        }
    }

    private static String runOcr(
            PDFRenderer renderer, int pageIndex, Path tempDirectory, String executable,
            String language, int dpi, int timeoutSeconds) throws IOException {
        Path imagePath = tempDirectory.resolve("page-" + (pageIndex + 1) + ".png");
        Path outputPath = tempDirectory.resolve("page-" + (pageIndex + 1) + ".txt");
        Path errorPath = tempDirectory.resolve("page-" + (pageIndex + 1) + ".err");
        try {
            if (!javax.imageio.ImageIO.write(renderer.renderImageWithDPI(pageIndex, dpi), "png", imagePath.toFile())) {
                throw new IOException("Unable to encode the rendered page for OCR.");
            }
            Process process = new ProcessBuilder(executable, imagePath.toString(), "stdout", "-l", language)
                    .redirectOutput(outputPath.toFile())
                    .redirectError(errorPath.toFile())
                    .start();
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new IOException("Tesseract OCR timed out on PDF page " + (pageIndex + 1) + ".");
                }
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Tesseract OCR was interrupted on PDF page " + (pageIndex + 1) + ".", exception);
            }
            if (process.exitValue() != 0) {
                throw new IOException("Tesseract OCR failed on PDF page " + (pageIndex + 1) + ": "
                        + readErrorExcerpt(errorPath));
            }
            if (Files.size(outputPath) > MAX_OCR_OUTPUT_BYTES) {
                throw new IOException("Tesseract OCR output exceeded the 1 MiB per-page limit on page " + (pageIndex + 1) + ".");
            }
            return Files.readString(outputPath, StandardCharsets.UTF_8).strip();
        } finally {
            Files.deleteIfExists(imagePath);
            Files.deleteIfExists(outputPath);
            Files.deleteIfExists(errorPath);
        }
    }

    private static String readErrorExcerpt(Path errorPath) throws IOException {
        if (Files.notExists(errorPath)) return "no diagnostic output";
        long size = Files.size(errorPath);
        try (InputStream input = Files.newInputStream(errorPath)) {
            byte[] bytes = input.readNBytes((int) Math.min(size, 2048));
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path);
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) Files.deleteIfExists(path);
        }
        Files.deleteIfExists(directory);
    }

    public record ExtractionResult(
            int pageCount, int usableTextPages, int ocrPages, Path layoutPath, Path provenancePath) { }
}
