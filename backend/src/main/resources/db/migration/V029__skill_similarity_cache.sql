-- Phase 2: Semantic Matching — cache for LLM-evaluated skill-pair similarity.
--
-- The table stores bidirectional similarity scores so the system never
-- re-asks the LLM for a pair it has already evaluated. Keys are
-- normalised lower-case; the application layer ensures skill_a < skill_b
-- (lexicographic) so "React"↔"ReactJS" is stored once, not twice.

create table if not exists skill_similarity_cache (
    skill_a       text    not null,
    skill_b       text    not null,
    score         real    not null check (score >= 0.0 and score <= 1.0),
    source        text    not null default 'LLM',   -- LLM | SYNONYM | EXACT
    model_key     text,                              -- which model produced the score (null for non-LLM)
    created_at    timestamptz not null default now(),
    primary key (skill_a, skill_b)
);

comment on table skill_similarity_cache is
    'Caches pairwise skill similarity scores to avoid repeated LLM calls. Keys are normalised lower-case with skill_a < skill_b.';

create index if not exists idx_skill_sim_cache_b
    on skill_similarity_cache (skill_b, skill_a);
