-- Cover-letter checksum binding. Cover letters are append-only versions, but
-- unlike CVs (V016) they carried no stored digest, so nothing could prove the
-- bytes downloaded for an application are the bytes that were generated and
-- reviewed. Every new insert records sha256(body_markdown); existing rows are
-- backfilled with the same digest so the whole history becomes verifiable.
alter table cover_letters add column if not exists content_sha256 text;

update cover_letters
set content_sha256 = encode(digest(body_markdown, 'sha256'), 'hex')
where content_sha256 is null;

comment on column cover_letters.content_sha256 is
  'hex sha256 of body_markdown at generation time; verified when the letter is bound into an execution package';
