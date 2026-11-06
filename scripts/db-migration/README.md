# Database migration: backup and restore-test tooling

These scripts implement Stages A–C of [`docs/ROBIN_DB_MIGRATION_PHASE2_PLAN.md`](../../docs/ROBIN_DB_MIGRATION_PHASE2_PLAN.md):

1. A verified, encrypted backup of the production database.
2. A restore of that backup into an isolated PostgreSQL 17 instance, checked against the source.

They run on Windows PowerShell 5.1 or later, and every PostgreSQL tool runs inside the pinned `postgres:17.11-bookworm` image.

## Safety properties

- **Read-only against the source.**
  - Every source statement runs in one `REPEATABLE READ, READ ONLY` transaction, so the server itself rejects any write.
  - Large tables are touched only by `count`/`sum` aggregates and partial-index lookups. No sort, join, `DISTINCT` or `GROUP BY` runs on them, so no temp files are written on a nearly full disk.
- **One snapshot.** That transaction exports its snapshot. In that snapshot it records per-table row counts and content fingerprints. It then holds the snapshot open while `pg_dump --snapshot` copies exactly the same state.
- **Plaintext only in RAM.**
  - The unencrypted archive exists only in a container `tmpfs` and disappears with the container.
  - In the restore test, the data directory and the decrypted archive are also on `tmpfs`.
  - Only the AES-256 encrypted archive and metadata files reach the backup folder.
- **No secrets on command lines or on disk (live mode).** The database password and the encryption passphrase are typed at prompts inside the container (`docker exec -it`). They are never echoed, logged or passed as arguments.
- **No personal data in outputs.** Metadata files hold counts, SHA-256 values, fingerprints (hash sums) and object names. The manifest leaves out the host and user names.
- **Pinned image.** The scripts refuse to run any `postgres:17.11-bookworm` whose image id is not the reviewed `sha256:665dc7b3…` (override with `-ExpectedImageId`).
- **Safe folders only.** The backup folder must be outside OneDrive and outside this repository. Nothing here commits or uploads anything.

## Requirements

- Docker Desktop running.
- The image, pulled once:

  ```
  docker pull postgres:17.11-bookworm
  ```

- `C:\RobinBackups` (or your `-BackupRoot`).
- For the Flyway check only: Maven and JDK 21, as for the backend build.

## Files

| File | Purpose |
|---|---|
| `dry-run.ps1` | Stage A. Runs Stages B and C against a synthetic, Supabase-shaped source on this machine. |
| `backup-supabase.ps1` | Stage B. Takes the read-only backup and encrypts it. |
| `restore-test.ps1` | Stage C. Restores into an isolated PostgreSQL 17 and verifies the result. |
| `common.ps1` | Shared helpers. |
| `container/backup.sh`, `container/run-pg-dump.sh` | Run inside the backup container. |
| `container/restore.sh` | Runs inside the restore container. |
| `container/copy-counts.awk` | Counts rows per table in the archive. |
| `sql/snapshot-session.sql` | The read-only snapshot session: facts, fingerprints, inventory, then `pg_dump`. |
| `sql/table-fingerprints.sql` | Row count plus an order-independent content fingerprint for every table. |
| `sql/inventory.sql` | Schema inventory: tables, indexes, constraints, triggers, functions, RLS, sequences, Flyway. |
| `sql/outbox-analysis.sql` | Pending-outbox analysis. Run on the restored copy only. |
| `sql/dryrun-source-*.sql` | Build the synthetic dry-run source. Never run these on a real database. |
| `backend/.../config/ExternalDatabaseFlywayIT.java` | Flyway with the application's configuration against a given database. It is disabled unless a URL is passed. |

## Stage A: dry run (no production access)

```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\dry-run.ps1
```

The dry run does the following:

1. Builds a throwaway source in the same shape as production:
   - `postgres` is a role without superuser but with `BYPASSRLS`;
   - `citext` is in `public`, and the other extensions are in `extensions`;
   - the `auth`, `storage`, `realtime` and `vault` schemas exist;
   - the `supabase_realtime` publication exists;
   - Supabase's `ensure_rls` event trigger is installed.
2. Migrates that source with the application's own guarded Flyway strategy: a baseline at 0, then V001–V051.
3. Adds synthetic rows.
4. Runs Stage B and Stage C exactly as they run for real.

It ends with `DRY RUN PASSED`. Results go to `C:\RobinBackups\dryrun\`.

## Stage B: the production backup (you run it)

**Before you start:**

- Copy the **Session pooler** host and user from the Supabase dashboard (**Connect → Session pooler**, port 5432). Don't use the transaction pooler (port 6543): the shared snapshot needs session mode.
- Create the encryption passphrase in your password manager. Use at least 16 characters; a long random one is best.
- Optional, for `-SslMode verify-full`: download the project's CA certificate from Database settings → SSL configuration, and pass it with `-SslRootCert <file>`. Otherwise TLS is `require`.

**Run it from Windows Terminal or PowerShell.** It needs an interactive console.

```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\backup-supabase.ps1 -PgHost <session-pooler-host> -PgUser <session-pooler-user>
```

**What you will be asked for:**

1. The database password, once.
2. After the dump, the encryption passphrase, twice.

The script then decrypts the archive once to prove it decrypts correctly. Neither value is echoed.

**What to expect:**

- The fingerprints read every table once.
- `pg_dump` then copies about 1.0–1.6 GB through the pooler; this is an estimate.
- Allow tens of minutes.
- Supabase counts that traffic as egress.

**Output:** `C:\RobinBackups\<UTC time>-live\`

| File | Content |
|---|---|
| `robin.dump.gpg` | The backup, encrypted. |
| `robin.dump.sha256`, `robin.dump.gpg.sha256` | Hashes of the plain and encrypted archive. |
| `manifest.txt` | Versions, snapshot time, outbox counts, sizes, hashes, check totals. |
| `checks.txt` | One PASS/FAIL/NOTE line per check. |
| `host-checks.txt` | The SHA-256 of the copy on this machine. |
| `source-fingerprints.txt`, `source-inventory.txt`, `source-facts.txt` | The source, as seen in the snapshot. |
| `archive-counts.txt`, `count-comparison.txt`, `toc.txt` | What the archive contains. |
| `pg_dump.log`, `backup.log` | Logs. |

**It passes only when:**

- every line in `checks.txt` is PASS or NOTE;
- `host-checks.txt` is PASS.

The checks cover:

- server major version 17;
- `pg_dump` exit 0 with no errors in its log;
- the table-of-contents entries for `public` match the snapshot inventory: table data, row security, triggers, views, sequences and indexes;
- every table's archive row count equals its snapshot row count;
- the decryption round trip.

**Afterwards:**

- Copy `robin.dump.gpg` and `manifest.txt` to your offsite location. Copy only the encrypted file.
- Even after a pass, the backup is only "taken". It is "verified" once Stage C passes.

## Stage C: isolated restore test

```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\restore-test.ps1 -RunDir C:\RobinBackups\<UTC time>-live
```

You will be asked for the encryption passphrase. This also proves your password-manager copy is right.

The test restores schema `public` as `robin_app`, a role with `NOSUPERUSER NOBYPASSRLS` that owns every object, as planned for Oracle. It then checks:

- the inventory equals the snapshot's;
- no constraint is `NOT VALID`;
- every table's row count and content fingerprint equal the snapshot;
- the archive yields the same row counts as at backup time;
- the data outside `public` (`auth`, `storage`, `realtime`, `vault`) is readable;
- RLS is enabled and inert for the owner, while a role without `BYPASSRLS` sees 0 rows;
- every pending outbox event is preserved;
- Flyway validate passes with the application's configuration.

The report goes to `<run>\restore-test-<UTC time>\`. It includes `outbox-analysis.txt`, which feeds the resume decision.

**Memory:** a production-size restore keeps the data directory and the decrypted archive in RAM. Allow roughly 5 GB of free memory for Docker, and close large applications first. `-DataTmpfsGb` sets the cap on the data directory (6 GB by default).

## Troubleshooting

- **Docker Desktop fails with "dockerInference … cannot be accessed by the system".**
  - Cause: a stale socket from an earlier session.
  - Fix: with Docker stopped, rename `%LOCALAPPDATA%\Docker\run` (for example to `run.stale`) and start Docker again.
- **"Max client connections" or "too many clients" from the pooler.**
  - The backend holds 10 pooler sessions, and the backup needs 2.
  - Retry later. Pausing the backend is a separate, approved production change.
- **`NOTE|snapshot_session`.** The pooler closed the idle snapshot session after `pg_dump` had started. The archive is unaffected, because the fingerprints were taken before `pg_dump` ran.
- **"live mode needs an interactive terminal".** Run the command from Windows Terminal or a PowerShell window, not from a non-interactive runner.
- **"Port … is already in use".** Pass `-HostPort` to `restore-test.ps1`, or `-SourcePort` / `-RestorePort` to `dry-run.ps1`.
