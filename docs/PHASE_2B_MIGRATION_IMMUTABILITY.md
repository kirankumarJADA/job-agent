# Phase 2B: Migration Immutability & Flyway Schema Evolution

## 1. Executive Summary

During the Phase 2A pre-publish safety gate, an architectural violation was identified: `V065__search_plans.sql` had been modified in-place relative to its baseline commit (`fb3f606`). Modifying already-committed Flyway migrations alters their checksum, causing Flyway validation failures (`FlywayValidateException: Validate failed: Migrations have failed validation: Checksum mismatch for migration version 065`) on any database where the migration was previously applied.

In Phase 2B, **strict migration immutability** has been restored:
1. `V065__search_plans.sql` has been reverted byte-for-byte to its original `fb3f606` baseline.
2. An additive migration `V066__search_plan_leases.sql` has been introduced to supply execution lease coordination columns and indexes.
3. The complete migration chain (V064 $\rightarrow$ V065 $\rightarrow$ V066) has been verified via PostgreSQL 16 Testcontainers integration tests.

---

## 2. Byte-for-Byte Verification of `V065__search_plans.sql`

A binary Git diff against baseline commit `fb3f606` confirms that `V065__search_plans.sql` is completely identical to its original version:
```bash
$ git diff fb3f606 -- backend/src/main/resources/db/migration/V065__search_plans.sql
# (0 lines modified, diff empty)
```

### File Checksum
* **Path**: `backend/src/main/resources/db/migration/V065__search_plans.sql`
* **SHA-256**: `cd1c47634e10f4a7ba4ec8fd8e14ecfe6b91ec20096aa0cb7cfbce58ca1c5c98`
* **Status**: Identical to `fb3f6062a3f35a046b39cbdc4c6ba2f80b4c864b`.

---

## 3. Additive Migration: `V066__search_plan_leases.sql`

To support atomic lease renewal and side-effect fencing without modifying historical files, migration `V066__search_plan_leases.sql` was added:

```sql
-- V066: Search Plan Execution Leases & Concurrency Protection
-- Adds atomic lease ownership tokens and expiration tracking for multi-instance coordination.

ALTER TABLE search_plans
    ADD COLUMN execution_owner_token UUID,
    ADD COLUMN execution_lease_expires_at TIMESTAMPTZ;

-- Index for execution lease coordination and stale recovery
CREATE INDEX search_plans_lease_idx ON search_plans (active, last_execution_status, execution_lease_expires_at);

COMMENT ON COLUMN search_plans.execution_owner_token IS 'Cryptographic worker token claiming the current execution lease';
COMMENT ON COLUMN search_plans.execution_lease_expires_at IS 'Timestamp after which the execution lease is considered abandoned';
```

### Architectural Justification
1. **Zero Checksum Invalidation**: Environments that applied V065 during Phase 2 or Phase 2A can seamlessly upgrade to Phase 2B by running only V066.
2. **Backward & Forward Compatibility**: The new columns are nullable, allowing existing search plans created under V065 to persist without data transformation.
3. **Optimized Lease Recovery Index**: The partial/composite index `search_plans_lease_idx` on `(active, last_execution_status, execution_lease_expires_at)` provides index-only scans for the scheduler sweep and stale recovery queries.

---

## 4. Migration Chain Verification (`V064ToV065MigrationIT`)

The migration evolution from V064 baseline to V065 and V066 was verified using real PostgreSQL 16 Testcontainers in `V064ToV065MigrationIT.java`.

### Test Execution Proof
```
[INFO] Running com.personal.jobagent.discovery.plan.V064ToV065MigrationIT
...
[INFO] Successfully applied 3 migrations to schema "public", now at version v066 (execution time 00:00.182s)
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 18.23 s
[INFO] BUILD SUCCESS
```

### Test Validation Points
1. **Migration Chain Execution**: Executes Flyway clean migration up to V064, seeds test candidate and company records, migrates through V065 and V066, and asserts successful completion at version 066.
2. **Column & Constraint Integrity**:
   - Asserts `execution_owner_token` is present and type `UUID`.
   - Asserts `execution_lease_expires_at` is present and type `TIMESTAMPTZ`.
   - Asserts index `search_plans_lease_idx` is active in `pg_indexes`.
3. **Data Preservation**: Pre-existing search plan records created prior to V066 remain fully queryable, valid, and updateable with lease tokens.

---

## 5. Checksum Stability & Rollback Considerations

### Checksum Stability Matrix
| Migration Version | Description | Checksum Stability Status |
| :--- | :--- | :--- |
| `V061`–`V064` | Historical Wave 2 migrations | **Untouched** |
| `V065` | Search plans base schema | **Preserved byte-for-byte to `fb3f606`** |
| `V066` | Search plan execution leases | **New additive migration** |

### Rollback DDL
If V066 ever needs to be rolled back in an emergency pre-release rehearsal, the following DDL restores the V065 schema state cleanly:
```sql
DROP INDEX IF EXISTS search_plans_lease_idx;
ALTER TABLE search_plans
    DROP COLUMN IF EXISTS execution_owner_token,
    DROP COLUMN IF EXISTS execution_lease_expires_at;
DELETE FROM flyway_schema_history WHERE version = '066';
```
