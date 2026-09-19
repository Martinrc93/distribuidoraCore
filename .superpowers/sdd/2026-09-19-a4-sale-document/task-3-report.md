# Task 3 Report

## Status

Complete.

## Commit Hashes

- `d1a49f1aa47f2aee6f670467c8d038fbe19ed986` — `feat: render sale documents as A4 PDF`

## Tests

- RED: `mvn -q -Dtest=OpenPdfA4RendererTest test` failed as expected because `OpenPdfA4Renderer` did not exist.
- Focused GREEN: `mvn -q -Dtest=OpenPdfA4RendererTest test` passed.
- Full backend suite: `mvn -q test` passed.

## Output Summary

Added a stateless OpenPDF 3.0.5 renderer that creates an A4 document in memory, renders sale metadata and a `PdfPTable` of line details, formats monetary values with `Locale.ROOT`, and includes total, paid, and pending amounts. Renderer failures are translated to `IllegalStateException`; no business data is mutated or loaded from a database.

## Concerns

- Maven/Mockito emitted the existing dynamic Java-agent attachment warning; it did not affect test results.
- The workspace already contained generated `backend/target` and frontend cache changes; those artifacts were not staged or intentionally modified.
- The report is intentionally separate from the feature commit so the requested feature commit message remains exact.
