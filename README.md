# Infrrd Mortgage Loan Review Search

A focused Spring Boot prototype for mortgage operations reviewers. It organises a loan package into document types and versions, searches extracted text, ranks matching pages, and opens the exact source page. The UI uses an Infrrd-aligned mortgage review workflow; this is an assignment prototype, not an official Infrrd product or integration.

## Run

Requires JDK 21 and Maven 3.9+. From the project root:

```bash
mvn spring-boot:run
```

Open `http://localhost:8080`. Run automated JUnit tests with `mvn test`. To run on another port in PowerShell:

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=9090"
```

Then open `http://localhost:9090`.

## Design

The code follows a small layered design: API controllers handle HTTP contracts; `PackageCatalogService` owns manifest refresh and snapshot publication; `PackageManifestValidator` checks inputs and path boundaries; `PageTextLoader` maps extracted sections to physical pages; and the search package contains a tokenizer, recursive-descent parser, immutable query AST, and positional inverted index. `SearchEngine` is responsible for Boolean/phrase evaluation, BM25-style scoring, snippets, pagination, and bounded result caches. The UI consumes the API and keeps document navigation separate from search results.

`package.json` is the source of truth for document type, version, and physical page ranges. Every request hashes the small manifest. A changed hash triggers validation and a complete rebuild; the new immutable snapshot is published with one volatile write, so readers never observe a partially refreshed index. The page-delimited `data/layout.txt` maps section N to physical PDF page N. AND, OR, NOT, parentheses, implicit AND, quoted phrases, and suffix wildcards such as `sign*` are supported. Ranking uses BM25-style term weighting and positional phrase checks. Results include source document metadata, physical page, snippet, and relevance score.

## Performance and memory

Measured on the supplied 560-page corpus with OpenJDK 21.0.12.1 using `./scripts/verify-core.sh` (plain-JDK benchmark; one local process, not an HTTP/load test):

- 560 physical pages; 280 pages have no extracted text; 161,253 indexed tokens and 6,571 unique terms.
- Latest run: layout read + page mapping 63.547 ms; index build 182.094 ms; first search for `"cash to close"` 26.826 ms (19 matching pages).
- 25 identical warm repeats: 1.658 ms median and 7.973 ms p95; 16.2× median improvement over the first measured search.

Numbers are machine/JVM dependent. The first-search timing starts after index construction; build timings are reported separately in the UI and benchmark. Memory is bounded by limits of 20,000 pages, 64 MiB layout text, 4 MiB manifest, 2,000,000 tokens, 100,000 unique terms, 128 prefix-cache entries (max 256 expansions each), 64 query-cache entries (max 5,000 page IDs per query), and 100 results per API page. Requests over those bounds are rejected or safely degraded rather than allowed to grow caches without limit.

## Package data and limitations

The supplied text corpus covers 560 physical pages and the reconstructed manifest defines 104 document entries with contiguous, non-overlapping page ranges. The original 144 MB PDF is not duplicated in this lightweight archive. To enable embedded PDF preview, copy it to `data/pdf/loan-package.pdf`; without it, page navigation and extracted-text review still work. Pages without a text layer stay visible in the document tree and viewer but cannot match full-text queries. No OCR is attempted.

The brief referenced a separate assignment ZIP, but only the assignment brief, sample PDF, and layout text were supplied. Therefore the original repository's `package.json`, starter code, and `AGENTS.md` were unavailable; document metadata was reconstructed from the supplied page text and visible page headings. Edit `package.json` to change document metadata or page ranges. The next API request detects the change, validates it, and rebuilds the snapshot. The index is in-memory and is not persisted across restarts. Authentication, OCR, PDF generation, and deployment are out of scope. With more time, I would add a real PDF/text extraction pipeline, larger-corpus benchmarks, concurrency/load tests, and an external index such as Lucene/OpenSearch for multi-instance scaling.

## Tests and validation

There are 29 JUnit tests across the search and package-catalog layers, covering Boolean precedence, phrase position, wildcard validation/expansion, Unicode normalization, ranking/pagination, cache bounds, missing text, package reload, manifest validation, path traversal, and stage timings. Run them with `mvn test`. The dependency-free search smoke checks and sample benchmark can be run with `bash scripts/verify-core.sh`; check the UI script syntax with `node --check src/main/resources/static/app.js`.

In the implementation environment, 15 dependency-free search checks passed, the full main/test Java source sets passed a Java 21 syntax/type check against temporary framework API stubs, the manifest was verified to cover all 560 pages without overlap or gaps, and the browser JavaScript syntax check passed. Maven was not installed and Maven Central could not be reached, so the real Maven dependency resolution, JUnit execution, and Spring Boot runtime remain unverified here. Please run `mvn test` and `mvn spring-boot:run` in a Maven-enabled environment before treating this as production-ready.

## AI assistance

ChatGPT (OpenAI) was used for coding support. `AI_USAGE_LOG.txt` is a manually compiled session summary because the original `AGENTS.md` logging hook was not supplied.
