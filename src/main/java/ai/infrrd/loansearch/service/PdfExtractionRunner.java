package ai.infrrd.loansearch.service;

import ai.infrrd.loansearch.domain.PackageManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the explicit offline PDF extraction command without starting the web server. */
@Component
@ConditionalOnProperty(name = "app.extract-pdf", havingValue = "true")
final class PdfExtractionRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PdfExtractionRunner.class);
    private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final PdfTextExtractor extractor;
    private final Path projectRoot;
    private final Path manifestPath;
    private final Path dataRoot;
    private final String outputRelativePath;
    private final boolean ocrEnabled;
    private final String tesseractExecutable;
    private final String ocrLanguage;
    private final int ocrDpi;
    private final int ocrTimeoutSeconds;

    PdfExtractionRunner(
            ObjectMapper objectMapper,
            PdfTextExtractor extractor,
            @Value("${app.project-root:.}") String projectRootValue,
            @Value("${app.package-manifest:package.json}") String manifestValue,
            @Value("${app.data-dir:data}") String dataDirValue,
            @Value("${app.extraction.output:generated/layout.txt}") String outputRelativePath,
            @Value("${app.ocr.enabled:false}") boolean ocrEnabled,
            @Value("${app.ocr.executable:tesseract}") String tesseractExecutable,
            @Value("${app.ocr.language:eng}") String ocrLanguage,
            @Value("${app.ocr.dpi:200}") int ocrDpi,
            @Value("${app.ocr.timeout-seconds:90}") int ocrTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.extractor = extractor;
        this.projectRoot = Path.of(projectRootValue).toAbsolutePath().normalize();
        this.manifestPath = PackageManifestValidator.resolveInside(this.projectRoot, manifestValue);
        this.dataRoot = PackageManifestValidator.resolveInside(this.projectRoot, dataDirValue);
        this.outputRelativePath = outputRelativePath;
        this.ocrEnabled = ocrEnabled;
        this.tesseractExecutable = tesseractExecutable;
        this.ocrLanguage = ocrLanguage;
        this.ocrDpi = ocrDpi;
        this.ocrTimeoutSeconds = ocrTimeoutSeconds;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("Package manifest is not a regular file: " + manifestPath);
        }
        if (Files.size(manifestPath) > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("package.json exceeds the 4 MiB manifest limit.");
        }
        PackageManifest manifest = objectMapper.readValue(manifestPath.toFile(), PackageManifest.class);
        PackageManifestValidator.validate(manifest, projectRoot, dataRoot);
        Path pdfDirectory = PackageManifestValidator.resolveInside(dataRoot, "pdf");
        Path pdfPath = PackageManifestValidator.resolveInside(pdfDirectory, manifest.pdfFile());
        Path outputPath = PackageManifestValidator.resolveInside(dataRoot, outputRelativePath);
        PdfTextExtractor.ExtractionResult result = extractor.extract(
                pdfPath, manifest.pdfFile(), outputPath, manifest.pageCount(), ocrEnabled,
                tesseractExecutable, ocrLanguage, ocrDpi, ocrTimeoutSeconds);
        log.info("Extracted {} PDF pages to {} ({} pages with usable text, {} OCR pages). Provenance: {}",
                result.pageCount(), result.layoutPath(), result.usableTextPages(), result.ocrPages(),
                result.provenancePath());
    }
}
