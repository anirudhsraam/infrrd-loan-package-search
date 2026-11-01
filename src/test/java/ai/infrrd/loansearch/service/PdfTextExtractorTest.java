package ai.infrrd.loansearch.service;

import ai.infrrd.loansearch.domain.PageTextMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfTextExtractorTest {
    @TempDir Path tempDir;

    @Test
    void extractsEveryPhysicalPageAndWritesVerifiedProvenance() throws Exception {
        Path pdf = createPdf();
        Path layout = tempDir.resolve("generated/layout.txt");
        PdfTextExtractor extractor = new PdfTextExtractor(new ObjectMapper());

        PdfTextExtractor.ExtractionResult result = extractor.extract(
                pdf, "loan-package.pdf", layout, 2, false, "tesseract", "eng", 200, 90);

        assertEquals(2, result.pageCount());
        assertEquals(1, result.usableTextPages());
        assertEquals(0, result.ocrPages());
        PageTextLoader loader = new PageTextLoader(new ObjectMapper());
        var pages = loader.readPages(layout, 2, pdf, "loan-package.pdf");
        assertTrue(pages.getFirst().text().contains("Mortgage loan income verification details"));
        assertEquals(PageTextMetadata.Source.PDF_TEXT, pages.getFirst().metadata().source());
        assertEquals(PageTextMetadata.Quality.USABLE, pages.getFirst().metadata().quality());
        assertEquals("", pages.get(1).text());
        assertEquals(PageTextMetadata.Quality.NO_TEXT, pages.get(1).metadata().quality());
        assertTrue(Files.isRegularFile(result.provenancePath()));
    }

    @Test
    void refusesToPublishTextWhenManifestPageCountDisagreesWithPdf() throws Exception {
        Path pdf = createPdf();
        Path layout = tempDir.resolve("generated/layout.txt");

        assertThrows(IllegalArgumentException.class, () -> new PdfTextExtractor(new ObjectMapper()).extract(
                pdf, "loan-package.pdf", layout, 3, false, "tesseract", "eng", 200, 90));
        assertFalse(Files.exists(layout));
        assertFalse(Files.exists(layout.resolveSibling("layout.txt.provenance.json")));
    }

    @Test
    void provenanceIsRejectedWhenTheSourcePdfHasChanged() throws Exception {
        Path pdf = createPdf();
        Path layout = tempDir.resolve("generated/layout.txt");
        new PdfTextExtractor(new ObjectMapper()).extract(
                pdf, "loan-package.pdf", layout, 2, false, "tesseract", "eng", 200, 90);
        Files.writeString(pdf, "changed", StandardCharsets.UTF_8);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PageTextLoader(new ObjectMapper()).readPages(layout, 2, pdf, "loan-package.pdf"));
        assertTrue(error.getMessage().contains("source PDF changed"));
    }

    private Path createPdf() throws Exception {
        Path pdf = tempDir.resolve("loan-package.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage pageWithText = new PDPage();
            document.addPage(pageWithText);
            try (PDPageContentStream content = new PDPageContentStream(document, pageWithText)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Mortgage loan income verification details for the borrower and reviewer");
                content.endText();
            }
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        return pdf;
    }
}
