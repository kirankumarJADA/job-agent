-- V004__outbox_attempt_tracking.sql
-- Discovered during P1-c implementation: the architecture's DLQ design
-- (§C4 — "DLQ = outbox rows failing N times flip published_at = NULL +
-- notification raised + metrics counter") requires tracking how many times
-- a row has failed, but V001's outbox_events has no such column. This is
-- an additive column, not a redesign of the approved schema — every
-- existing column, constraint, and index from V001 is untouched.

alter table outbox_events
  add column attempt_count int not null default 0,
  add column last_error text;
