# ROBIN — STAGE 3I PHASE 2: DURABLE RECEIPT LINKING & RECONCILIATION IMPLEMENTATION

**Date:** 2026-11-26
**Repository:** `kirankumarJADA/job-agent`
**Branch:** `integration/wave-3`
**PR:** `#21` — OPEN / DRAFT, unmerged, base `master` (state preserved)
**Baseline:** `4866e485c99c9c8777ee9863dbadd4dc1e88b03e` (Stage 3I Phase 1; CI run `45875384243`: backend, frontend, worker, cloudflare-email-worker all `success`)
**Design reference:** [`STAGE_3I_PHASE_1_MULTI_CHANNEL_RECEIPT_ARCHITECTURE.md`](./STAGE_3I_PHASE_1_MULTI_CHANNEL_RECEIPT_ARCHITECTURE.md)

**Verdict: COMPLETE** — all Gate 1–9 deliverables implemented and verified locally; GitHub Actions result for the pushed commit is reported with the commit (a document cannot contain its own CI run).

No production database was accessed, nothing was deployed, no employer portal was contacted and no real application was submitted. Every test runs against local fixtures, Testcontainers PostgreSQL 16 and a local Chromium.

---

## 0. Summary

| Gate | Result |
| :--- | :--- |
| 0 Preflight | Branch, HEAD (= origin), clean tree, PR #21 OPEN/DRAFT and CI `45875384243` (4/4 success) verified before any change. |
| 1 V071 | `application_receipt_links` added (additive, append-only, isolation trigger, fail-closed precheck, backfill). Proven against a populated V070 schema by 7 Testcontainers tests. |
| 2 Reconciler | Robin's submission is an ordinary evidence signal: Level B → C1 → D progression, terminal outcomes that preserve history, conflict priority, order independence. |
| 3 Reference conflicts | `SUBMISSION_REFERENCE_CONFLICT` between trusted browser-observed and trusted acknowledgement references; candidate resolution via the existing receipt review, recorded as a superseding link row. |
| 4 Temporal policy | Anchor chosen by provenance (pre-submit fence → submit approval → candidate-reported date); 5-minute skew; provider-assigned times only; unknown time never verifies. |
| 5 Evidence v2 | `robin-submission-evidence-v2`: correlation, redirect chain, final origin, identifier source, upload proof, redaction, canonical self-digest re-derived in Java. v1 still accepted. |
| 6 E2E tests | 13 production-route Testcontainers tests covering all 18 listed scenarios, plus unit, worker and real-Chromium tests. |
| 7 Frontend | Verification level, sandbox tag, Robin's submission record with time source, conflicts with both original values, contested receipts never green, upload proof in the receipt. |
| 8 Discipline | V001–V070 byte-identical; approval, PRE_SUBMIT_ATTEMPTING, SSRF/DNS/proxy, CAPTCHA/MFA and duplicate-submit fences untouched. |
| 9 Verification | Local Windows checks on the final code: backend Surefire unit 2,029 (0 failures, 6 skipped), worker 394 (393 passed, 1 POSIX skip), frontend `tsc -b` + 489 tests + build, cloudflare-email-worker 25 + phase-20 scripts 8; PostgreSQL 16 Testcontainers ITs verified on GitHub Actions Ubuntu CI run `45889637790` (Failsafe 464 run, 0 failures, 7 skipped). |

---

## 1. Gate 0 — Preflight and the architecture extended

- `integration/wave-3` at `4866e48`, equal to `origin/integration/wave-3`; working tree clean.
- PR #21: `OPEN`, `isDraft: true`, base `master`, head `4866e48`, `mergedAt: null`.
- CI `45875384243` on `4866e48`: `backend`, `frontend`, `worker`, `cloudflare-email-worker` — all `success`.

Extended, not duplicated:

| Existing component | Phase 2 change |
| :--- | :--- |
| `ApplicationReconciler` | Rewritten rules section (`track-reconcile-v2`); same inputs/outputs, `TrackVocabulary` only. |
| `TrackRepository` | Snapshot gains submission facts, anchors, excluded identifiers and foreign references; link reads/writes live here (no new repository). |
| `TrackEvidenceRecorder` | Computes the temporal assessment and reference comparison at ingestion; writes the ingestion link; records candidate review links. |
| `ReceiptVerificationPolicy` (track) | Takes the temporal assessment; version `track-receipt-policy-v2`. |
| `ApplicationTrackingService` | Passes the new snapshot fields; review appends a link row; API exposes level, sandbox, time sources and link decisions. |
| `ApplicationReceiptService` | Universal observation delegated to the shared `BrowserObservationPolicy`; receipt adds `browserEvidence`. |
| `SubmissionEvidenceRepository`, `JdbcReceiptStore`, `JdbcReceiptTrustStore`, `EmployerDomainPolicy` | Used as is (strict approval SQL, decrypt + digest check, domain binding). |
| `worker/src/universal_confirmation.js` | Evidence v2 builder, redirect-chain check, upload proof, redaction. |
| `EmailIntelligenceService` | Threads the connector `providerId` into `MessageFacts` (no other change). |

New classes are pure policies or one shared reader: `ReceiptTemporalPolicy`, `SubmissionReferencePolicy`, `receipt/CanonicalJson`, `receipt/UniversalSubmissionEvidence`, `receipt/BrowserObservationPolicy`, `receipt/BrowserObservationReader`. There is no parallel reconciliation engine and no second receipt repository.

---

## 2. Gate 1 — V071 durable receipt linking

### 2.1 Schema proved against V070 before use

`ReceiptLinkMigrationIT.schemaAssumptionsHoldAtV070` migrates a fresh database to V070 and asserts the exact column types V071 depends on (`application_receipts.id/external_reference/evidence_time/channel`, `submission_evidence.id/plan_id/employer_confirmation_id/raw_evidence_sha256 = character`, `automation_plans.profile_id`, `applications.profile_id`) and that `reject_receipt_record_update()` exists. V071 repeats the same check in a `DO` block and raises if any assumption fails, which rolls the whole migration back.

### 2.2 `application_receipt_links`

| Column group | Columns | Why |
| :--- | :--- | :--- |
| Identity & isolation | `id`, `profile_id` → profiles CASCADE, `application_id` → applications CASCADE | Purge works in one statement; every row is owner-scoped. |
| What was linked | `receipt_id` → application_receipts, `plan_id` → automation_plans, `submission_evidence_id` → submission_evidence, `supersedes_link_id` → self (all NO ACTION) | A linked receipt, plan or evidence row cannot be deleted directly; a purge still succeeds (checked at end of statement, as V069). |
| Decision | `origin` (`INGESTION`/`CANDIDATE_REVIEW`/`V071_BACKFILL`), `decision` (`LINKED`/`CANDIDATE_CONFIRMED`/`CANDIDATE_REJECTED`/`NOT_ASSESSED`), `actor`, `linked_at`, `policy_version`, `correlation` jsonb | Reproducible rationale (identity evidence, policy versions, temporal reason, evidence schema). |
| Provenance | `channel` (V038 values), `provider_id`, `receipt_verification`, `receipt_fingerprint`, `evidence_sha256` | Channel/provider identity and integrity at link time. |
| References | `receipt_reference`, `submission_reference` (both original), `reference_consistency`, `reference_detail` | Neither value is ever replaced; the comparison only reports. |
| Timing | `evidence_time`, `evidence_time_precision`, `evidence_time_source` (`PROVIDER_RECEIPT`/`CANDIDATE_SUPPLIED`/`NONE`), `anchor_time`, `anchor_precision`, `anchor_source`, `temporal_status`, `clock_skew_seconds` | Timestamp provenance on both sides of the comparison. |

CHECK constraints: evidence requires a plan; anchor time/source/precision consistent; evidence time/source/precision consistent; backfill rows are always `NOT_ASSESSED` with no comparison or temporal decision; only a candidate review supersedes, and it always does; ingestion rows are `LINKED`; a non-mailbox channel can never carry a `VERIFIED` receipt (mirrors V038).

Indexes: PK; `application_receipt_links_root_uq` (unique `receipt_id` where not superseding); `application_receipt_links_supersedes_uq` (unique `supersedes_link_id`); `(profile_id, application_id, linked_at)`; partial indexes on `submission_evidence_id` and `plan_id`.

Triggers: `application_receipt_links_consistent` (BEFORE INSERT) rejects any row whose application, receipt, plan, evidence or superseded row belongs to a different application or profile; `application_receipt_links_append_only` refuses UPDATE and direct DELETE (cascade only).

### 2.3 Schema decisions

1. **A new link table, not new columns on `application_receipts`.** Receipts carry a mutable candidate-review column and are keyed per application; a correlation decision is a separate, historical fact that can change (review) without rewriting the receipt. Append-only rows keep every decision.
2. **V069 untouched.** `submission_receipts` keeps `NOT NULL sandbox_registration_id` and `employer_confirmation_id`; sandbox registrations are never reused for real employer receipts.
3. **Trigger-enforced consistency instead of composite foreign keys.** Composite FKs would need new unique constraints on `application_receipts`, `submission_evidence` and `automation_plans` (index builds and locks on existing tables). The trigger gives the same guarantee for every write path, including direct SQL, and is tested.
4. **One current row per receipt** by construction (unique root + unique supersede ⇒ a linear chain); duplicate or concurrent writes use `ON CONFLICT DO NOTHING` and are no-ops.
5. **No columns added to `submission_evidence`.** Its rows are immutable; v2 facts are read from the encrypted payload after the existing decrypt-and-digest check rather than stored twice.
6. **Backfill records only stored facts.** Every pre-V071 receipt gets one `V071_BACKFILL`/`NOT_ASSESSED` root; no comparison or temporal decision is invented. Reconciliation does not depend on link rows, so historical receipts reconcile from their own facts.
7. **No webhook channel.** No employer webhook endpoint exists, so `WEBHOOK_CONNECTOR` is not admitted.

### 2.4 Migration evidence (`ReceiptLinkMigrationIT`, Testcontainers PostgreSQL 16, own database per test)

| # | Test | Proves |
| :--- | :--- | :--- |
| 0 | `schemaAssumptionsHoldAtV070` | Column types and helper function V071 relies on. |
| 1–2 | `upgradesPopulatedV070SchemaPreservingHistoricalReceiptsAndSandboxReferences` | Populated V070 (plan, evidence, sandbox registration, V069 receipt, two V038 receipts) upgrades; V069/V038 rows byte-identical; one NOT_ASSESSED root per receipt with stored facts; V069 NOT NULLs and immutability unchanged. |
| 3 | `preventsCrossProfileAndCrossApplicationLinks` | Foreign receipt, plan, evidence and owner mismatches rejected (`23514`). |
| 4 | `preventsDuplicateActiveLinks` | Second root and second supersede refused; exactly one current row; shape CHECKs. |
| 5 | `preservesHistoricalEvidenceAfterLaterRejectionOrWithdrawal` | Rejected receipt + withdrawn application keep both rows; UPDATE/DELETE refused; linked receipt/plan undeletable; profile purge cascades. |
| 6 | `rollsBackSafelyOnInvalidSchemaAssumptions` | A drifted column makes V071 fail with no table, function or success row left; restored, it applies and backfills. |
| 7 | `declaresIndexesAndForeignKeys` | Indexes, FK targets and delete rules (`c`/`a`), both triggers. |

`git diff 4866e48 -- backend/src/main/resources/db/migration` adds only `V071__durable_receipt_links.sql`.

---

## 3. Gate 2 — Reconciliation transitions (`track-reconcile-v2`)

Robin's own submission becomes an ordinary signal: stage `APPLIED`, basis `VERIFIED` only for a V069 sandbox receipt, otherwise `UNVERIFIED`, time = `submission_evidence.recorded_at` (server clock). The furthest eligible signal then decides, exactly as for every other source, so the previous early return at `APPLIED` is gone.

| From | Evidence | To | Basis / labels |
| :--- | :--- | :--- | :--- |
| Level A (`RECONCILIATION_REQUIRED`) | — | `READY_TO_APPLY` + attention; never retried | submission `ATTEMPT_RECORDED` |
| Level B (`SUBMITTED`, clean browser observation) | — | `APPLIED` | `Applied — not verified`, level `BROWSER_OBSERVED`; no mandatory review item |
| `SUBMITTED`, evidence failed checks | — | `APPLIED` + `RECEIPT_UNVERIFIED` attention | level `ATTEMPT_RECORDED` |
| C2 sandbox receipt | — | `APPLIED` | `Applied — verified (sandbox)`, `[SANDBOX]` reason, `sandbox = true` |
| B / A / manual | C1 authenticated acknowledgement, uncontested | `ACKNOWLEDGED` | `VERIFIED`, submission `CONFIRMED` / `EMPLOYER_EMAIL_VERIFIED` |
| any | unverified acknowledgement | `ACKNOWLEDGED` | `UNVERIFIED` (amber) |
| ACKNOWLEDGED/APPLIED | candidate-confirmed or reported IN_CONTACT / ASSESSMENT / INTERVIEW / OFFER (Level D) | that stage | `CANDIDATE_CONFIRMED` / `CANDIDATE_REPORTED`; acknowledgement and submission dimensions unchanged |
| any | rejection / reported withdrawal not older than the furthest progress | `REJECTED` / `WITHDRAWN` | submission record and acknowledgement still reported |
| any | Robin `WITHDRAWN` / `FAILED` | `WITHDRAWN` / `STOPPED` | submission record still reported |
| any | any conflict | `NEEDS_REVIEW` | basis null; conflicts listed with original values |

Rules kept or added: inferences never move the stage (pending suggestion only); a candidate report is never `VERIFIED`; an older message never overwrites a newer stage (monotonic progress; terminal only when not older); same-day offer vs rejection is `OUTCOME_CONFLICT`. Determinism: all inputs are sorted by `(time, id)` and every `max` has a total tie-break, so `ApplicationReconcilerProgressionTest.outOfOrderInputsReconcileToTheSameStateWhateverTheOrder` reconciles 25 shuffles to an identical `TrackState`.

**Pre-existing defect found and fixed:** `AutomationController#markApplicationSubmitted` mirrors a Robin submission onto the pre-8.3 status with actor `worker` and `event_key automation.real_submit:*`; the V038 trigger classifies such rows as `CANDIDATE_REPORTED`, which made a Level B submission read "reported by you" once the early return was removed. TRACK now ignores `automation.*` status mirrors (read-side; history and V038 untouched). Recorded in the gap register.

---

## 4. Gate 3 — Submission-reference conflict detection

`SubmissionReferencePolicy` (`track-reference-policy-v1`):

- **Normalisation:** strip surrounding whitespace and a leading `#`, upper-case. Separators are kept (`APP-123` ≠ `APP123`): a false mismatch sends the candidate to review; a false match would hide a conflict. Malformed values (outside `[A-Z0-9][A-Z0-9._/-]{2,63}`) are not comparable.
- **Only confirmation identifiers:** Robin's application, job and plan ids, the job's posting id (URL path) and its `job_identity_keys` (requisition and ATS keys) are excluded on both sides (`NOT_A_CONFIRMATION_ID`).
- **Only trusted evidence:** the acknowledgement must be `VERIFIED` (C1), and the browser reference must come from intact worker evidence (decrypts, server digest matches, worker provenance, v2 self-digest valid). Unverified acknowledgements and untrusted evidence are never compared.
- **Cross-application:** a verified acknowledgement linked to one application that quotes a reference Robin recorded for another of the owner's applications is also a conflict.
- **Effect:** `SUBMISSION_REFERENCE_CONFLICT` → `NEEDS_REVIEW`, submission `CONFLICTING`, acknowledgement not verified; the conflict carries `acknowledgement:<value>`, `browser-observed:<value>` and the receipt id; `RESOLVE_REFERENCE_CONFLICT` attention points at the receipt.
- **Resolution:** the existing receipt review. Confirm → basis `CANDIDATE_CONFIRMED` (never `VERIFIED`); reject → excluded. Either appends a `CANDIDATE_REVIEW` link row superseding the current one; both references remain as recorded.

Tested: matching, case/`#` differences, separators, missing, malformed, conflicting, duplicated, requisition/posting/internal ids, untrusted evidence, unverified acknowledgement, cross-application (unit), and the full route with candidate resolution (IT).

---

## 5. Gate 4 — Temporal policy and timestamp provenance

| Timestamp | Where it comes from | Trusted for | Used as |
| :--- | :--- | :--- | :--- |
| `applications.created_at` | Robin record creation | When Robin started tracking | **Not** an anchor (a candidate may have applied before) |
| `automation_plans.created_at` | Plan creation | When preparation started | **Not** an anchor |
| `automation_steps.created_at` of `pre-submit-attempting` | Server `now()` on the single-shot fence insert, immediately before the click | Earliest possible submission of that attempt | Anchor `PRE_SUBMIT_ATTEMPT` (EXACT) |
| `audit_logs.created_at` of `*_SUBMIT_APPROVED` | Server, at candidate approval | A click can never precede it | Anchor `SUBMIT_APPROVAL` for a real attempt without a fence |
| Candidate `SUBMITTED` report `occurredOn` | Candidate | Their own statement | Anchor `CANDIDATE_REPORTED_SUBMISSION` (DATE) |
| `submission_evidence.recorded_at` | Server `clock_timestamp()` at `/complete` (after the click) | Time of Robin's record | Time of the Level B/C2 signal; not a lower bound |
| Evidence `observedAt` | Worker clock | Display only | Never trusted for ordering |
| Gmail `internalDate` | Google | Arrival at the mailbox | Evidence time `PROVIDER_RECEIPT` |
| Managed-mail `signedAt` | HMAC-verified webhook timestamp (±900 s) | Delivery to Robin | Evidence time `PROVIDER_RECEIPT` |
| Candidate-forwarded "received" | Candidate | Their statement | Evidence time `CANDIDATE_SUPPLIED` |
| Message `Date` header | Sender | Nothing | Never reaches TRACK as a time |

Policy (`ReceiptTemporalPolicy`, `track-temporal-policy-v1`): the anchor is the earliest of the per-plan anchors (fence, else approval) and the candidate's reported date, keeping its source. Exact comparisons allow `CLOCK_SKEW = 5 min`; date comparisons allow one day. Outcomes: `EVIDENCE_TIME_UNKNOWN` (never verified), `FUTURE_DATED` (provider time > now + 5 min, candidate/date time > now + 1 day → `REJECTED`), `BEFORE_SUBMISSION` (→ `UNVERIFIED` at ingestion and `RECEIPT_TEMPORAL_CONFLICT` at reconciliation), `NO_SUBMISSION_ANCHOR`, `PLAUSIBLE`. Delayed delivery is plausible. Timestamps are never repaired. Reconciliation re-applies the policy, so a receipt ingested before Robin's attempt is checked against the attempt once it exists. Confirming a timing-contested receipt resolves it as `CANDIDATE_CONFIRMED`.

Tested: pre-submission email, skew at and beyond 5 minutes, delayed delivery, future provider and candidate times, missing/unknown times, no anchor, date-precision anchors, anchor selection, time source by channel (unit); before-fence, future-dated and post-crash acknowledgements on real routes (IT).

---

## 6. Gate 5 — Universal browser evidence v2

Contract `robin-submission-evidence-v2` (worker `universal_confirmation.js`; Greenhouse keeps v1):

| Field | Content |
| :--- | :--- |
| `attemptCorrelation` | `planId`, `applicationId`, `jobId` |
| `observedAt`, `observation` | worker time, `{source: PLAYWRIGHT_BROWSER, clock: WORKER_CLOCK}` |
| `request` | method, sanitised URL, status, observedAt of the accepted same-origin submission request |
| `redirectChain` | each main-frame hop during the submit click: `{host, status}` |
| `finalOrigin`, `confirmationPageUrl` | confirmation page origin and sanitised URL |
| `confirmationContainerHtml`, `confirmationText` | sanitised (scripts/inputs/secrets removed) and personal data redacted |
| `confirmationNumber`, `confirmationIdentifierSource` | genuine identifier only (internal/mock/requisition ids disqualified) and where it was seen (`RESPONSE_JSON`, `CONTAINER_ATTRIBUTE`, `CONTAINER_TEXT`, `CONTAINER_TEXT_PATTERN`) |
| `signals` | as v1 |
| `documents.{cv,coverLetter}` | `artifactSha256` (only when attached), `formAttachment`, `submissionRequestFilePart`, `bytesTransmissionObserved: false`, `uploadVerification` |
| `submittedCvSha256`, `submittedCoverLetterSha256` | only for `FILE_PART_OBSERVED` |
| `employerVerification` | always `NOT_VERIFIED_BY_BROWSER` |
| `redaction` | `{secrets: REDACTED, personalData: REDACTED}` |
| `evidenceSha256` | SHA-256 of the canonical JSON of every other field |

Upload proof never claims an upload from a selection. `ATTACHED` means a file input held a file with the artifact's exact name and size whose local bytes hash to the package digest (checked before attaching, re-checked at submit). `FILE_PART_OBSERVED` additionally means the accepted submission request carried a multipart file part with that name. A probe showed Chromium never exposes multipart file bytes to automation, even through route interception, so `bytesTransmissionObserved` is always `false` and no record says an employer received a file. Files uploaded in an earlier page's request are reported `ATTACHED_NOT_CONFIRMED`. File names are compared in memory and never persisted.

Security: any off-origin hop (not only the final page) → `UNEXPECTED_ORIGIN_REDIRECT`, not confirmed. The candidate's name, e-mail, phone and profile values, plus any e-mail address or phone number, are redacted from evidence text and HTML while the genuine identifier is preserved. SSRF/DNS pinning, the egress proxy, CAPTCHA/MFA stops, the PRE_SUBMIT_ATTEMPTING fence and duplicate-submit refusal are unchanged; an uncertain outcome is still `RECONCILIATION_REQUIRED` and never retried.

Server side: `BrowserObservationReader` (shared by the receipt view and TRACK) loads owner-scoped evidence, decrypts and checks the server digest, parses the payload and applies `BrowserObservationPolicy`: approval, stored raw evidence, integrity, worker provenance, signals, and for v2 the self-digest (re-derived by `CanonicalJson.java`), correlation and the exact-host redirect/final-origin rule. v1 and unversioned evidence pass on the pre-v2 checks (`BROWSER_OBSERVED_LEGACY_SCHEMA`), with documents reported `NOT_RECORDED`. Cross-language parity is pinned by `contracts/submissionEvidenceV2Fixtures.json` (canonical strings with control characters, non-ASCII, emoji, lone surrogates, large integers, plus a sealed v2 record), checked by both suites.

---

## 7. Gate 6 — End-to-end reconciliation tests

`ReceiptReconciliationIT` (SpringBootTest + Testcontainers PostgreSQL 16, production routes: approve-submit, prepare-submit, worker claim, `/steps` fence, `/complete`, connector ingestion, `/track`, `/receipt`, receipt and message review, candidate reports):

| # | Scenario | Test |
| :--- | :--- | :--- |
| 1, 3 | Level B → verified C1 → RECEIVED (ACKNOWLEDGED, verified); durable link with plan, evidence, digest, references, anchor, provider | `levelBThenVerifiedAcknowledgementAdvancesToReceivedWithADurableLink` |
| 2 | Level B → unverified email | `levelBThenAnUnverifiedEmailStaysUnverified` |
| 4, 5, 8 | Interview (D) confirmed; older acknowledgement arriving later; rejection preserving submission evidence | `laterInterviewThenRejectionPreserveSubmissionEvidenceAndOlderMessagesNeverWin` |
| 6 | Withdrawal preserving evidence | `withdrawalPreservesTheBrowserObservedSubmission` |
| 7, 17 | Duplicate and 8-way concurrent delivery → one receipt, one link | `duplicateAndConcurrentDeliveriesProduceExactlyOneReceiptAndOneLink` |
| 9 | Conflicting identifiers → NEEDS_REVIEW; candidate resolution → superseding link | `conflictingConfirmationReferencesNeedReviewAndTheCandidateResolvesIt` |
| 10, 11 | Before the fence → conflict; future → REJECTED | `anAcknowledgementBeforeTheSubmitAttemptAndAnImpossibleFutureOneAreNeverVerified` |
| 12 | Cross-profile read, review and attachment refused | `anotherProfileCanNeitherReadNorReviewNorAttachToTheseReceipts` |
| 13, 15 | Tampered v2 digest; off-host redirect in evidence | `tamperedDigestOrOffHostRedirectEvidenceIsNeverABrowserObservation` |
| 14 | Missing document upload proof | `missingDocumentUploadProofIsShownHonestlyWithoutForcingReview` |
| 16 | Crash after PRE_SUBMIT_ATTEMPTING: second fence 409, RECONCILIATION_REQUIRED, later acknowledgement checked against the fence, plan never moved | `afterACrashPastTheFenceNothingRetriesAndALaterAcknowledgementIsCheckedAgainstTheFence` |
| 17 | Concurrent candidate reviews → one decision | `concurrentReviewsOfOneReceiptAppendExactlyOneDecision` |
| 18 | v1 evidence and pre-V071 receipts | `historicalV1EvidenceAndPreV071ReceiptsStillReconcile` (+ `ReceiptLinkMigrationIT`) |

Unit level: `ApplicationReconcilerProgressionTest` (19), `ReceiptTemporalPolicyTest` (9), `SubmissionReferencePolicyTest` (7), `BrowserObservationPolicyTest` (6), `SubmissionEvidenceV2ContractTest` (3), `ReceiptVerificationPolicyTest` (+1 temporal). Worker: `universal_evidence_v2.test.js` (10, including a real-Chromium multipart submission) and the existing real-Chromium Universal slice, now asserting v2. Redirect rejection is also tested in the worker.

---

## 8. Gate 7 — Frontend and candidate-facing tracking

- Submission line shows the verification level: `Browser-observed — employer has not verified` (amber), `Sandbox receipt verified` (sky) with a `SANDBOX — not a live employer` tag, `Employer acknowledgement verified` (green only here), `Attempt recorded`, `Reported by you`, `Conflicting evidence` (red).
- The header no longer says "confirmed by verified employer evidence" for a sandbox receipt.
- Robin's submission record: source, server-recorded time, the reference the browser saw, and "a browser observation is not employer verification".
- Each receipt shows where its time came from (mail provider vs entered by you); a receipt whose link records a reference or timing contradiction shows `Conflicts with the submission` in red instead of `Verified`, with the existing confirm/reject buttons.
- Conflicts list both original values with provenance; the header pill is red while a conflict exists.
- Receipt modal: per-document upload proof (`Included in the submitted form`, `Attached — inclusion not confirmed`, …; never "uploaded", never green) and a browser-evidence block (assessment, pages seen, final origin).
- Ordinary unambiguous applications need no review: a clean Level B has no attention item.
- Application-list summaries carry `submissionLevel` and `sandbox`.

---

## 9. Gate 8 — Security and migration discipline

No production database, deployment, employer contact or real submission. No V001–V070 change (`git diff` shows only V071 added). Approval (`STRICT_APPROVAL_SQL`), the PRE_SUBMIT_ATTEMPTING single-shot fence, egress proxy/DNS pinning, SSRF guards, CAPTCHA/MFA stops and duplicate-submit guards are unchanged. Evidence stays AES-GCM encrypted with the server digest; v2 adds redaction before encryption. PR #21 stays OPEN/DRAFT.

---

## 10. Gate 9 — Verification

All commands are the CI job commands (`.github/workflows/ci.yml`): local Windows host (Java 21.0.12, Maven 3.9.16, Node 22; Surefire unit, Worker, Frontend, and Cloudflare) plus GitHub Actions `ubuntu-latest` CI run `45889637790` (Docker / Testcontainers PostgreSQL 16 Failsafe ITs).

| Suite | Command | Result |
| :--- | :--- | :--- |
| Backend unit (Surefire, local Windows) | `mvn -B test` (backend) | **2,029 run, 0 failures, 0 errors, 6 skipped** |
| Backend integration (Failsafe, GitHub Actions Ubuntu `45889637790`) | `mvn -B verify` (backend CI) | **464 run, 0 failures, 0 errors, 7 skipped** — `BUILD SUCCESS` |
| … of which Phase 2 migration | `ReceiptLinkMigrationIT` | 7 / 7 |
| … of which Phase 2 end-to-end | `ReceiptReconciliationIT` | 14 / 14 |
| … new Phase 2 unit classes | `ApplicationReconcilerProgressionTest` 19, `ReceiptTemporalPolicyTest` 9, `SubmissionReferencePolicyTest` 7, `BrowserObservationPolicyTest` 6, `SubmissionEvidenceV2ContractTest` 3, `ReceiptVerificationPolicyTest` +1 | 45 / 45 |
| Worker | `npm test` (worker) | **394 run, 393 passed, 0 failed, 1 skipped** (SIGTERM test, POSIX-only; runs on Linux CI); includes `universal_evidence_v2.test.js` 10 / 10 and the real-Chromium Universal slice asserting v2 |
| Frontend | `npx tsc -b`, `npm test`, `npm run build` | type check clean; **49 files, 489 tests passed**; build succeeds |
| Cloudflare email worker | `npm test --prefix cloudflare-email-worker`; `node --test scripts/phase20/*.test.mjs` | 25 / 25; 8 / 8 (unchanged code) |

> **Post-`19be6de` hardening verification note:** Following the follow-up fail-closed schema/origin hardening (`UniversalSubmissionEvidence.Kind.UNKNOWN_VERSION`, `EVIDENCE_UNPARSEABLE`, missing `finalOrigin` rejection in `BrowserObservationPolicy`) and UI truthfulness fixes (`ApplicationsPage` `SubmittedPlanNotice` / `TrackStatusPill`, `trackShared` `stageTone` / `submissionTone` sandbox & conflict styling, and `ApplicationTrackPanel` conflict pill/text consistency), local runs verified Backend Surefire **2,029 run, 0 failures, 0 errors, 6 skipped** (Redis diagnostic/AB/TLS tests), Frontend **49 files, 489 tests passed** (+ `tsc -b` + `vite build`), Worker **394 run, 393 passed, 1 skipped** (POSIX-only SIGTERM test), and Cloudflare **25/25** + probes **8/8**. Local Failsafe Testcontainers ITs could not start because Docker Engine was unavailable on the Windows host (`Could not find a valid Docker environment`), so Testcontainers PostgreSQL 16 ITs are verified via the GitHub Actions `ubuntu-latest` CI run for the pushed commit (`19be6de` also passed all 4 CI jobs in run `45889637790`, including Failsafe **464 run, 0 failures, 0 errors, 7 skipped**).

Existing tests changed on purpose (each a rule this stage changes, not a weakened check):

- `ReceiptVerificationPolicyTest.anUnknownEvidenceTimeIsNotFabricatedAndDoesNotFailVerification` → `…AndCannotBeVerified`: Gate 4 requires an unknown provider time to produce an unverified outcome.
- `ApplicationReconcilerTest.aConfirmedRobinSubmissionIsAppliedAndVerified`: the label is now `Applied — verified (sandbox)` and the submission view carries `sandbox = true` (Gate 2: sandbox verification stays visibly sandbox-tagged).
- `worker/test/universal_engine.test.js`: Universal evidence is asserted as v2 (digest, correlation, redirect chain, upload proof) instead of v1.
- Shared receipt contract fixtures (`backend/.../contracts/submissionReceiptFixtures.json`, `frontend/src/test/fixtures/…`): regenerated from the real DTOs to add `browserEvidence` (only that key changed).

Diff discipline: `git diff 4866e48 --stat -- backend/src/main/resources/db/migration` shows only the new `V071__durable_receipt_links.sql`; V001–V070 are byte-identical. PR #21 was not merged, not marked ready for review, and remains a Draft.

---

## 11. Remaining security and external-validation blockers

1. **Stage 3G external validation remains BLOCKED** — no operator authorisation record, staging host, secrets or teardown plan.
2. **Live mail delivery unverified** — Cloudflare `TRUSTED_AUTHSERV_IDS` alignment and real Edge headers, live Gmail sync; C1 has only been exercised through the connector fixture.
3. **`UNIVERSAL` excluded from trusted receipt policy** — no Level C2 for Universal (by design pending review).
4. **Upload bytes unobservable** — browser proof tops out at `FILE_PART_OBSERVED`; earlier-page uploads stay `ATTACHED_NOT_CONFIRMED`.
5. **No anchor for manual applications without a reported date** — only the future-dated rule applies.
6. **Outlook/IMAP connectors and employer webhooks** — still stubs / absent; no `WEBHOOK_CONNECTOR`.
7. **Carried forward:** session key not provisioned; enterprise SSO on a sibling subdomain unsupported (now also rejected by the v2 redirect rule); proxy covers browser traffic only; backend `AddressGuard` re-resolution gap; PDF cover-letter path lacks an end-to-end test.
8. **V038 trigger** still labels Robin's status mirrors as candidate reports for any reader other than TRACK.

---

## 12. Recommended next stage

**Stage 3I Phase 3 — Live mailbox C1 validation under authorisation.** Once an operator authorisation exists: align `TRUSTED_AUTHSERV_IDS` on the Cloudflare worker and backend, inspect one real Edge `Authentication-Results` header on a reserved test alias, and run the Stage 3G harness end to end so that a synthetic Universal submission (Level B, evidence v2) is followed by a genuinely authenticated acknowledgement (C1) linked through V071. In parallel, without external access: decide the Universal Level C2 policy, and add an earlier-page upload observer so `FILE_PART_OBSERVED` covers multi-page forms.
