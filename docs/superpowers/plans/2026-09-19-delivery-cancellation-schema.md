# Delivery Cancellation Schema Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PostgreSQL-compatible delivery and cancellation persistence through a V7 Flyway migration, without changing service/controller behavior.

**Architecture:** Extend the existing `orders.orders` and `sale.sales` tables with nullable lifecycle timestamps, preserving existing rows. Add an append-only `orders.delivery_attempts` table with a user foreign key, constrained result/observation semantics, and indexes for order history and attempt ordering.

**Tech Stack:** PostgreSQL, Flyway, Spring Boot Maven project, JUnit 5, AssertJ.

## Global Constraints

- Follow ADR-007 business states: `CONFIRMED`, `DELIVERED`, `CANCELLED`.
- Store technical timestamps as nullable `TIMESTAMPTZ`.
- Preserve existing seeded rows and remain PostgreSQL-compatible.
- Add contract coverage before production SQL.
- Do not implement service or controller behavior.
- Do not create a Git commit.

---

### Task 1: Contract Test and V7 Migration

**Files:**
- Create: `backend/src/test/java/com/distribuidora/order/DeliveryLifecycleMigrationContractTest.java`
- Create: `backend/src/main/resources/db/migration/V7__add_delivery_cancellation_support.sql`

**Interfaces:**
- Consumes: Existing Flyway SQL resource conventions and the V3 commercial table relationships.
- Produces: A V7 migration contract for lifecycle timestamps and append-only delivery attempts.

- [ ] **Step 1: Write the failing contract test**

  Read `/db/migration/V7__add_delivery_cancellation_support.sql`, normalize whitespace/case, and assert:
  - nullable `delivered_at` and `cancelled_at` columns on both `orders.orders` and `sale.sales`;
  - `orders.delivery_attempts` with UUID primary key, order FK, required positive attempt number, result, observation, required user FK, and `TIMESTAMPTZ` attempt time;
  - `DELIVERED`/`FAILED` result check;
  - failed attempts require nonblank observation while delivered attempts allow null;
  - indexes on `(order_id, attempt_number)` and `(order_id, attempted_at)`;
  - no destructive statements.

- [ ] **Step 2: Run the focused test and verify it fails**

  Run from `backend`:

  ```text
  mvn -Dtest=DeliveryLifecycleMigrationContractTest test
  ```

  Expected: FAIL because the V7 migration resource does not yet exist.

- [ ] **Step 3: Add the minimal V7 migration**

  Use `ALTER TABLE ... ADD COLUMN ... NULL` for both timestamp pairs. Create `orders.delivery_attempts` with `attempted_by UUID NOT NULL REFERENCES identity.users (id)`, `attempt_number INTEGER NOT NULL`, `result VARCHAR(20) NOT NULL`, `observation TEXT NULL`, and `attempted_at TIMESTAMPTZ NOT NULL`. Enforce positive attempt numbers, allowed results, and `result <> 'FAILED' OR (observation IS NOT NULL AND btrim(observation) <> '')`. Add indexes for per-order attempt history and attempt ordering. Do not seed, update, or delete existing rows.

- [ ] **Step 4: Run the focused test and verify it passes**

  Run:

  ```text
  mvn -Dtest=DeliveryLifecycleMigrationContractTest test
  ```

  Expected: PASS.

- [ ] **Step 5: Run full verification**

  Run:

  ```text
  mvn test
  mvn package
  ```

  Expected: all tests pass and the package succeeds.
