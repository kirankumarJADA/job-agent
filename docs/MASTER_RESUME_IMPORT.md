# Master Resume Import

Lets a candidate upload an existing PDF/DOCX resume and populate most of
their Master Profile from it, instead of typing every experience, education,
skill and certification entry by hand.

## Why a two-stage preview/confirm flow

Resume text is AI-extracted, so it can be incomplete, mis-grounded, or wrong
about which existing profile entry it corresponds to. Nothing from a resume is
ever written to the Master Profile automatically:

1. `POST /api/v1/profile/resume-import/preview` (multipart, one PDF or DOCX
   file, field name `file`) reads the file, extracts structured data through
   Robin's existing `ModelRouter`/`LlmProvider` routing (no separate AI
   provider or key), source-grounds every atomic fact against the original
   text, compares each item with the caller's current Master Profile, and
   returns an editable preview plus a short-lived `previewToken`. **Nothing is
   persisted to the profile.**
2. `POST /api/v1/profile/resume-import/confirm` takes the user's edited,
   selected and approved items with that `previewToken` and applies only those,
   through the existing `ProfileRepository` insert/update methods, so evidence
   status and the master-revision triggers behave exactly as they do for a
   manually entered record.

Ownership always comes from the authenticated principal (`AppUserDetails` →
`ProfileRepository.findByUserId`). No profile id is accepted from the client,
and confirm also requires the caller's profile to be the one the preview was
issued for.

## Confirm is one transaction

- The token is claimed first, with one `UPDATE ... WHERE consumed_at IS NULL
  AND expires_at > now() RETURNING`. A concurrent second confirm of the same
  token waits on that row lock and then finds it used (409). A token belonging
  to another account, an expired one, or an unknown one is refused the same way.
- Any later failure rolls back every change **and** the claim, so the same
  preview can simply be retried. A database failure returns 503 ("Nothing was
  changed").
- No statement inside the transaction is allowed to fail on a constraint and
  be carried on past (PostgreSQL would abort the whole transaction). Duplicates
  are detected up front instead (see below).

## What is stored

Nothing about the uploaded resume, neither its bytes nor its extracted text, is
written to the database, the logs or the audit trail. The upload exists only
for the request: Tomcat spools the multipart part to its temp directory and
Spring's multipart cleanup deletes it when the request ends. The only state
kept between the two calls is a `resume_import_previews` row (V053): the
token's owner, the profile it was computed against, and its expiry. A user's
spent (used or expired) rows are deleted when they next preview, and deleting a
profile deletes its previews.

Audit events (`PROFILE_RESUME_IMPORT_PREVIEWED`, `PROFILE_RESUME_IMPORT_CONFIRMED`)
carry only counts, the detected file type, whether the offline fallback was
used, and the token id.

## Duplicate detection and merge rules

`ProfileComparisonService` classifies every extracted item against the caller's
existing records as `NEW`, `EXISTING`, `POSSIBLE_DUPLICATE`, `CONFLICT` or
`NEEDS_REVIEW`: a normalized identity match on employer+title,
institution+qualification, project name, skill name or certification
name+issuer, then dates, URLs and credential ids decide `EXISTING` versus
`CONFLICT`. Skill names keep their punctuation (C, C# and C++ are different
skills) and compare case-insensitively, like the database's unique index.

- The review defaults every `NEW` item to "add" and leaves everything else
  unselected. `EXISTING` items cannot be selected at all. Selecting a possible
  duplicate or conflict adds it as a separate entry; replacing an existing
  record is always a separate, explicit choice.
- Confirm re-checks every "add" against the current records plus everything
  added earlier in the same request, and skips (with a warning) anything that
  is now an exact duplicate, so repeated imports cannot duplicate records.
- A "replace" is applied only if the targeted record still exists **in the
  caller's own profile**. A skill replace changes only the name and category;
  the user's own mastery, years and evidence link are kept.
- Personal fields are applied only when explicitly included. Only `headline`,
  `phone`, `location`, `linkedinUrl` and `websiteUrl` are accepted; anything
  else (including `email`) is ignored, so the account's identity can never
  change here. Link fields must be a plain address or `http(s)`.
- Nothing is ever deleted because a resume omits it.

## AI extraction, grounding and prompt-injection handling

`ResumeProfileExtractor` treats the resume as untrusted data: it is wrapped in
an explicit "content, not instructions" block (the pattern
`ApplicationAnswerService` already uses), and any copy of the block's delimiter
tags inside the resume is defused first, so the resume cannot "close" the block
and have the rest read as instructions. The model is told to use `null`/`[]`
for anything absent rather than guess, at temperature 0, with an 8192-token
output budget (`LlmCompletionRequest.simple()`'s 512-token cap would be passed
to every real provider as `max_tokens` and truncate any realistic resume). Inline
`<think>` reasoning blocks are removed before the JSON is parsed.

Every atomic fact (employer, title, institution, qualification, project name,
skill name, certification name) is then checked against the original extracted
text. A fact not found there is marked `sourceVerified: false` /
`requiresReview: true` rather than dropped or trusted.

### Offline fallback

When no real provider answers, `ModelRouter` falls back to
`SimulatedProvider`, which for this task runs a small, linear-time heuristic
parser in-process (the resume never leaves the server). The preview then
carries `offlineFallback: true`, every item is marked for review, and the
review screen says plainly that the AI provider was unavailable.

## Limits

10 MB per file; file type from the file's signature, not its name or
Content-Type; at most 12 PDF pages and 50,000 characters of extracted text
(larger documents are refused, never silently truncated); 20 s to extract
text; 45 s per AI attempt; previews expire after 30 minutes; at most 1,000
items per confirm. Encrypted, corrupt, legacy `.doc`, and scanned (no text)
documents are refused with a specific message. There is no OCR.

## Relationship to Dossier

Dossier (the reference implementation named in the original spec) is a
Next.js/Prisma app; nothing from its stack was copied. The two-stage
preview/confirm shape, source-grounded extraction, and
new/existing/duplicate/conflict/needs-review classification are the same
*concepts*, reimplemented on Robin's existing Spring/JDBC `ProfileRepository`,
`ModelRouter` and audit conventions. Robin differs in that replacement is
always a distinct, explicit per-item action, nothing from the resume is kept at
rest, and the preview/confirm boundary is an opaque single-use server-side
token rather than trusting whatever the client resubmits.

## Known limitations

- No OCR: a scanned/image-only PDF is refused with a message asking for a
  text-based PDF or a DOCX.
- The review edits identity fields and dates; bullet text, project
  descriptions and certification details are imported as extracted.
- Per-experience `technologies` are shown for context but are not separately
  imported as skills; only the resume's skills section is.
- With several providers configured, a failing provider can be retried in
  turn, each attempt bounded by 45 s, so a preview can take longer than one
  attempt.
