-- V051__managed_mail_cloudflare_provider.sql
-- Phase 20: Support Cloudflare Email Routing as managed-mail inbound provider.
--
-- 1. Expands provider_id checks in managed_mail_domains, managed_mail_webhook_tokens,
--    and managed_inbound_messages to allow 'cloudflare'.
-- 2. Expands local_part check in managed_mail_addresses to support plus-addressing
--    (e.g., robin+<token>), compatible with Cloudflare's literal base routing rules.

alter table managed_mail_domains drop constraint if exists managed_mail_domains_provider_id_check;
alter table managed_mail_domains add constraint managed_mail_domains_provider_id_check
    check (provider_id in ('mailgun', 'cloudflare'));

alter table managed_mail_webhook_tokens drop constraint if exists managed_mail_webhook_tokens_provider_id_check;
alter table managed_mail_webhook_tokens add constraint managed_mail_webhook_tokens_provider_id_check
    check (provider_id in ('mailgun', 'cloudflare'));

alter table managed_inbound_messages drop constraint if exists managed_inbound_messages_provider_id_check;
alter table managed_inbound_messages add constraint managed_inbound_messages_provider_id_check
    check (provider_id in ('mailgun', 'cloudflare'));

alter table managed_mail_addresses drop constraint if exists managed_mail_addresses_local_part_check;
alter table managed_mail_addresses add constraint managed_mail_addresses_local_part_check
    check (local_part ~ '^[a-z][a-z0-9+_-]{11,63}$');
