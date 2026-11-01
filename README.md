# Infrrd Mortgage Loan Review Search

A focused Spring Boot prototype for mortgage operations reviewers. It organises a loan package into document types and
versions, searches extracted text, ranks matching pages, and opens the exact source page. The UI uses an Infrrd-aligned
mortgage review workflow; this is an assignment prototype, not an official Infrrd product or integration.

## Run

Requires JDK 21 and Maven 3.9+. From the project root:

```bash
mvn spring-boot:run
```

Open `http://localhost:9090` (the default port). Run automated JUnit tests with `mvn test`. To run on another port in
PowerShell:

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=9090"
```

Then open `http://localhost:9090`.

## Design

The code follows a small layered design: API controllers handle HTTP contracts; `PackageCatalogService` owns manifest
refresh and snapshot publication; `PackageManifestValidator` checks inputs and path boundaries; `PageTextLoader` maps
extracted sections to physical pages; and the search package contains a tokenizer, recursive-descent parser, immutable
query AST, and positional inverted index. `SearchEngine` is responsible for Boolean/phrase evaluation, BM25-style
scoring, snippets, pagination, and bounded result caches. The UI consumes the API and keeps document navigation separate
from search results.

`package.json` is the source of truth for document type, version, and physical page ranges. Every request hashes the
small manifest. A changed hash triggers validation and a complete rebuild; the new immutable snapshot is published with
one volatile write, so readers never observe a partially refreshed index. The page-delimited `data/layout.txt` maps
section N to physical PDF page N. AND, OR, NOT, parentheses, implicit AND, quoted phrases, and suffix wildcards such as
`sign*` are supported. Ranking uses BM25-style term weighting and positional phrase checks. Results include source
document metadata, physical page, snippet, and relevance score.

The source-text viewer preserves form and disclosure content in reading order instead of guessing table structure from
spacing alone. It renders a table only when page context and column headings identify bank-account activity or payroll
data; transaction amounts are aligned to their date, description, withdrawal/deposit, and balance columns, and numeric
values are right-aligned for comparison. Other aligned fields remain text, and the original extracted text is available
through the viewer toggle.

## Performance and memory

Measured on the supplied 560-page corpus with OpenJDK 21.0.12.1 using `./scripts/verify-core.sh` (plain-JDK benchmark;
one local process, not an HTTP/load test):

- 560 physical pages; 280 pages have no extracted text; 161,253 indexed tokens and 6,571 unique terms.
- Latest run: layout read + page mapping 63.547 ms; index build 182.094 ms; first search for `"cash to close"` 26.826 ms
  (19 matching pages).
- 25 identical warm repeats: 1.658 ms median and 7.973 ms p95; 16.2× median improvement over the first measured search.

Numbers are machine/JVM dependent. The first-search timing starts after index construction; build timings are reported
separately in the UI and benchmark. Memory is bounded by limits of 20,000 pages, 64 MiB layout text, 4 MiB manifest,
2,000,000 tokens, 100,000 unique terms, 128 prefix-cache entries (max 256 expansions each), 64 query-cache entries (max
5,000 page IDs per query), and 100 results per API page. Requests over those bounds are rejected or safely degraded
rather than allowed to grow caches without limit.

## Package data and limitations

The supplied text corpus covers 560 physical pages and the reconstructed manifest defines 104 document entries with
contiguous, non-overlapping page ranges. The original 144 MB PDF is not duplicated in this lightweight archive. To
enable embedded PDF preview, copy it to `data/pdf/loan-package.pdf`; without it, page navigation and extracted-text
review still work. Pages without a text layer stay visible in the document tree and viewer but cannot match full-text
queries. No OCR is attempted.

The brief referenced a separate assignment ZIP, but only the assignment brief, sample PDF, and layout text were
supplied. Therefore the original repository's `package.json`, starter code, and `AGENTS.md` were unavailable; document
metadata was reconstructed from the supplied page text and visible page headings. Edit `package.json` to change document
metadata or page ranges. The next API request detects the change, validates it, and rebuilds the snapshot. The index is
in-memory and is not persisted across restarts. Authentication, OCR, PDF generation, and deployment are out of scope.
With more time, I would add a real PDF/text extraction pipeline, larger-corpus benchmarks, concurrency/load tests, and
an external index such as Lucene/OpenSearch for multi-instance scaling.

## Tests and validation

There are 32 JUnit tests across the search and package-catalog layers, covering Boolean precedence, phrase position,
wildcard validation/expansion, Unicode normalization, ranking/pagination, cache bounds, missing text, package reload,
manifest validation and size limits, path traversal, and stage timings. Run them with `mvn test`. The dependency-free
search smoke checks and sample benchmark can be run with `bash scripts/verify-core.sh`; check the UI script syntax with
`node --check src/main/resources/static/app.js`.

The current implementation has been verified with `mvn test` (32 tests passing),
`node --check src/main/resources/static/app.js`, and a local Spring Boot smoke check of the UI and health endpoint. The
table renderer was also checked across the supplied page text: it recognized bank activity and payroll tables while
leaving loan application and underwriting form layouts as text. Maven compiles for the project's Java 21 target; the
available local runtime used Java 25. These checks do not replace production-scale load, browser compatibility, or
deployment testing.

## AI assistance

GitHub Copilot was used for coding support.
