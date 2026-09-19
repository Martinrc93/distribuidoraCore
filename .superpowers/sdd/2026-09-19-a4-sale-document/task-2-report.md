# Task 2 Report

## Status

Complete.

## Commit Hashes

- `7993e7c` — `feat: load immutable sale document snapshots`

## Tests

- RED: `mvn -q -Dtest=SaleDocumentServiceTest test` failed as expected because the Task 2 service and exceptions did not exist.
- Focused GREEN: `mvn -q -Dtest=SaleDocumentServiceTest test` passed.
- Full backend suite: `mvn -q test` passed.

## Output Summary

Added a repeatable-read, read-only `SaleDocumentService.load(UUID)` that maps the persisted sale header, customer and seller display values, sale-item snapshots, payment rows, total, and paid amount without invoking pricing or mutation services. Missing orders and orders without sales now produce distinct exceptions. Lines and payments use deterministic database ordering.

## Concerns

- Maven/Mockito emitted the existing dynamic Java-agent attachment warning; it did not affect test results.
- The workspace already contained generated `backend/target` changes; those artifacts were not staged or intentionally modified.
- The service maps `line_discount_percent` directly to the document model's `discount` field because Task 2 requires persisted snapshot loading without recalculation.
