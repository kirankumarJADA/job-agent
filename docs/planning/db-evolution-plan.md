# Phase 1 Database Evolution Plan

**No migration files are written in this document.** This is a plan for what, if anything, each remaining P1 slice needs from Flyway, given that `V001__core.sql` already created all 29 Phase-1 tables up front (verified — see the dependency map for the full list) and `V002__seed.sql` already applied dev seed data.

## Ground rule this plan follows

`V001` and `V002` are **already applied** to a live database (verified in the P1-a review session). Per Flyway convention, applied migrations are immutable — any future correction to them must be a new versioned migration (e.g. an `ALTER TABLE`), never an edit to `V001__core.sql`/`V002__seed.sql` themselves, except in a throwaway local dev database being reset from scratch. This plan assumes migrations are additive from here on.

## Slice-by-slice migration needs

| Slice | New tables needed? | New seed/data migration needed? | Reasoning |
|---|---|---|---|
| P1-b (Auth + audit) | **No** | **No** | Uses existing `users` and `audit_logs`. Session storage is in-memory `HttpSession` (single instance, single user, Phase 1) — no `sessions` table needed unless you want DB-backed sessions for restart-survival, which is a decision worth confirming but isn't in the architecture doc as a requirement. |
| P1-c (Events core) | **No** | **No** | Uses existing `outbox_events`, `consumed_events`. Pure application-code slice. |
| P1-d (Profile & preferences) | **No** | **No** | All seven target tables already exist. Pure application-code slice. |
| P1-e (Multi-LLM) | **No** | **Yes — recommended** | `llm_providers`/`llm_models` tables exist but are empty; `V002` never seeded provider/model rows. Recommend a new `V003__llm_provider_seed.sql` inserting the `nim` and `gemini` provider rows plus their known models, once real model identifiers are confirmed with you (I don't want to guess NIM/Gemini model_key strings and bake wrong ones into a migration). |
| P1-f (Benchmarks) | **No** | **No (DB-wise)** | `model_benchmark_runs`/`benchmark_results`/`routing_policies` already exist. The actual work here is a **dataset file** (`datasets/benchmarks/job_classification/v1.jsonl`, 20 cases), not a migration — data curation, not schema. |
| P1-g (Jobs read-side) | **No** | **No, deliberately** | `jobs`/`job_sources`/`companies`/etc. already exist. Real scraped postings should be imported via an application-level path (dev-gated admin action or CLI), **not** a Flyway migration — see conflict review §7 for why. |

## What this means concretely

Out of all seven P1 sub-slices, only **one new migration file** is currently anticipated for the rest of Phase 1: `V003__llm_provider_seed.sql` in P1-e, and even that's a seed-data migration (inserting fixed reference rows for known providers/models), not a schema change. No `ALTER TABLE` is currently anticipated anywhere in Phase 1 — the architecture's decision to write the complete Phase-1 schema in `V001` up front (rather than incrementally per slice) is holding up so far under this review.

## Corrective-migration contingency

If the JPA/jsonb-array impedance risk (conflict review §3) materializes during P1-d's spike and requires a column type change (e.g., switching a `text[]` column to `jsonb` for easier ORM mapping), that would be the first genuine schema-altering migration of Phase 1: `V004__<description>.sql` (assuming V003 lands first per above), written as an explicit `ALTER TABLE ... TYPE ...` with an accompanying data-migration `USING` clause, and it would need to be flagged to you specifically because it changes a column the architecture doc's DDL (§C2) specified explicitly — that's a deviation from the approved schema, not a routine addition, and should get the same review this document is getting now, not be slipped in quietly.

## Numbering reserved so far

- `V001__core.sql` — applied (P1-a)
- `V002__seed.sql` — applied (P1-a)
- `V003__llm_provider_seed.sql` — reserved for P1-e, not yet written
- `V004+` — reserved for contingency only (see above), not currently planned
