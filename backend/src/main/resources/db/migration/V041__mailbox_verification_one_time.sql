-- A received verification message can be resolved only once.
-- Audit rows reference the original email; they do not persist an OTP or tokenized URL.
create unique index if not exists mailbox_verifications_source_email_once_idx
    on mailbox_verifications(source_email_id)
    where source_email_id is not null;
