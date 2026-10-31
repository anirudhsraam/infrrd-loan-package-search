package ai.infrrd.loansearch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads one extracted-text section per physical PDF page, preserving empty sections. */
final class PageTextLoader {
    private static final Logger log = LoggerFactory.getLogger(PageTextLoader.class);
    private static final long MAX_LAYOUT_BYTES = 64L * 1024 * 1024;

    List<String> readPages(Path layoutPath, int expectedPages) {
        if (!Files.isRegularFile(layoutPath)) {
            log.warn("Text layout is missing ({}); continuing with empty page text.", layoutPath);
            return emptyPages(expectedPages);
        }
        try {
            if (Files.size(layoutPath) > MAX_LAYOUT_BYTES) {
                throw new IllegalArgumentException("Extracted layout text exceeds the 64 MiB input limit.");
            }
            String[] sections = Files.readString(layoutPath, StandardCharsets.UTF_8).split("\\f", -1);
            List<String> pages = new ArrayList<>(expectedPages);
            for (int page = 0; page < expectedPages; page++) {
                pages.add(page < sections.length ? sections[page] : "");
            }
            if (sections.length < expectedPages) {
                log.warn("Text file contains {} sections for {} PDF pages; omitted pages remain available without searchable text.",
                        sections.length, expectedPages);
            } else if (sections.length > expectedPages + 1) {
                log.warn("Text file has extra sections ({}); ignoring content after physical page {}.", sections.length, expectedPages);
            }
            return pages;
        } catch (IOException exception) {
            log.warn("Unable to read text layout ({}); continuing with empty page text.", exception.getMessage());
            return emptyPages(expectedPages);
        }
    }

    private static List<String> emptyPages(int pageCount) {
        return new ArrayList<>(Collections.nCopies(pageCount, ""));
    }
}
