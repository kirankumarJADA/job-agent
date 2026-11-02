-- Phase 4 (job discovery and recommendations experience): the candidate
-- feed's full-text search (`q`) only ever matched title (weight A) and
-- description (weight B) — searching for an employer's own name (e.g.
-- "Doctolib") found nothing, even though the task explicitly asks for
-- "search by title, employer and job-description text." This widens the
-- existing generated `search_vector` column to also index
-- `company_name_raw` at weight C (lower than title/description, so an
-- employer-name match never outranks a genuine title/skill match).
--
-- A STORED generated column's expression cannot be altered in place
-- (Postgres has no `alter column ... set generated`), so the column and its
-- GIN index are dropped and recreated with the wider expression. Existing
-- rows are recomputed automatically when the column is re-added (Postgres
-- populates a new generated column for every existing row as part of the
-- ADD COLUMN itself) — no explicit backfill statement is needed or safe to
-- hand-write here.
--
-- No new index beyond the recreated GIN index is added by this migration.
-- The other Phase 4 filters (location, remote_type, employment_type,
-- experience_level, salary, sponsorship, posted_at) are all added as plain
-- WHERE-clause predicates in JobRepository, not new indexed columns: at this
-- product's actual data volume (a development/early-stage catalogue, not a
-- production-scale index of millions of postings — see Phase 3's own
-- report), a sequential scan with the existing `jobs_status_idx`
-- (status, first_seen_at desc) already narrowing most queries is adequate,
-- and `job_analyses(job_id, created_at desc)` (V001) already supports the
-- sponsorship filter's "latest analysis per job" lookup efficiently. Adding
-- speculative indexes with no query-plan evidence they are needed would
-- violate the task's own "only where justified by the actual query plan and
-- data volume" instruction.

drop index if exists jobs_fts_idx;
alter table jobs drop column search_vector;
alter table jobs add column search_vector tsvector generated always as
    (setweight(to_tsvector('english', coalesce(title,'')),'A') ||
     setweight(to_tsvector('english', left(description_text, 20000)),'B') ||
     setweight(to_tsvector('english', coalesce(company_name_raw,'')),'C')) stored;
create index jobs_fts_idx on jobs using gin(search_vector);
