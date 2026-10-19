-- Phase 4: Rate-limit / cost / quota engine
-- Tracks daily usage budgets for LLM and discovery operations.

create table if not exists usage_quotas (
    profile_id    uuid       not null,
    quota_key     text       not null,     -- e.g. 'llm_daily', 'discovery_daily'
    period_start  date       not null,
    used          real       not null default 0.0,
    quota_limit   real       not null,     -- max allowed in this period
    unit          text       not null default 'calls',  -- 'calls', 'tokens', 'cost_usd'
    updated_at    timestamptz not null default now(),
    primary key (profile_id, quota_key, period_start)
);

create index if not exists idx_usage_quotas_profile on usage_quotas (profile_id, quota_key);

-- Provider-level cost tracking (aggregated from llm_calls)
create or replace view provider_cost_summary as
select
    provider_id,
    date_trunc('day', created_at)::date as day,
    count(*) as call_count,
    coalesce(sum(input_tokens), 0) as total_input_tokens,
    coalesce(sum(output_tokens), 0) as total_output_tokens,
    coalesce(sum(est_cost), 0) as total_cost
from llm_calls
where ok = true
group by provider_id, date_trunc('day', created_at)::date;
