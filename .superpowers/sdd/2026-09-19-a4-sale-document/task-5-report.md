# Task 5 Report

## Status

PASS. Task 5 completed on `main`.

## Changes

- Documented `GET /api/orders/{orderId}/documents/a4` in `backend/README.md`, including permissions, headers, statuses, errors, read-only behavior, on-demand generation, and lack of persistent files.
- Marked the A4 document milestone as partially applied in `docs/development/backend-implementation-roadmap.md`; tickets, WhatsApp, and remaining notification work stay pending.
- Added `DocumentReadOnlyRegressionTest`, which guards the service path against database mutation interactions and verifies the persisted snapshot remains unchanged by loading.
- Extended `scripts/order-confirmation-smoke.ps1` with a non-destructive PDF assertion after restart. It checks status `200`, PDF content type, `%PDF` bytes, non-zero content, `venta-*.pdf` disposition, and unchanged order status/customer balance.
- The smoke uses the existing Compose lifecycle and persistent `postgres-data` volume; it does not run `down` or remove volumes.

## Commits

- `docs: document on-demand A4 sale documents`

## Verification

- Focused regression: `mvn -q -Dtest=DocumentReadOnlyRegressionTest test` passed.
- Backend suite: `mvn test` passed, 117 tests, 0 failures/errors/skips.
- Backend package: `mvn package -DskipTests` passed.
- Frontend build: `npm run build` passed.
- Compose validation: `docker compose config` passed.
- Smoke syntax parse passed.
- PostgreSQL Compose smoke passed after correcting binary PDF reads to use `RawContentStream`; lifecycle, restart, PDF, and persistent-volume checks all passed.

## Concerns

- Maven tests emit the existing Mockito/Byte Buddy dynamic-agent warning on this JDK; it does not fail the build.
- Build and smoke commands leave generated `backend/target` and frontend cache changes in the worktree; no generated target artifact is included in the Task 5 commit.

## Review Fixes

The follow-up review findings were addressed:

- Corrected the stale README statement so A4 on-demand documents are documented as available while tickets and WhatsApp remain pending.
- Marked A4 generation, API download, and A4 printing as complete in Fase 6; tickets, WhatsApp, email, and outbox remain pending.
- Replaced the interaction-only regression with an isolated H2 persistence test. It generates a real PDF through `SaleDocumentService` and `OpenPdfA4Renderer`, then compares order/sale status, inventory quantity and movement count, payment count/total, ledger count/total, and customer balance before and after.
- Extended the PostgreSQL smoke snapshot to compare the same inventory, payment, and ledger state in addition to order/sale status and customer balance.

## Review-Fix Verification

- Focused regression: `mvn -q -Dtest=DocumentReadOnlyRegressionTest test` passed.
- Backend suite: `mvn test` passed, 117 tests, 0 failures/errors/skips.
- Backend package: `mvn package -DskipTests` passed.
- Frontend build: `npm run build` passed.
- Compose validation and smoke syntax parse passed.
- PostgreSQL Compose smoke passed with expanded inventory/payment/ledger invariants and persistent-volume safety.

## Review-Fix Commit

- `fix: address Task 5 review findings`
