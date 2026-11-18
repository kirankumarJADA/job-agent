-- V060__emails_thread_id.sql
-- Add thread_id to emails table for threading recruiter replies across Gmail and RFC-compliant mailboxes.

alter table emails add column if not exists thread_id text;
create index if not exists emails_profile_thread_idx on emails (profile_id, thread_id) where thread_id is not null;
