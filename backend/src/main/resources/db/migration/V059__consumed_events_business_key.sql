-- V059__consumed_events_business_key.sql
-- Add business_key to consumed_events for idempotency per business key across outbox replays and duplicates.

alter table consumed_events add column if not exists business_key text;
create unique index if not exists consumed_events_consumer_bkey_uq
    on consumed_events (consumer, business_key)
    where business_key is not null;
