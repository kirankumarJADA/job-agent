# ROBIN — Phase 21 Verification: Candidate Knowledge System Integrity

**Prepared:** 2026-11-03  
**Status:** implementation and CI verification complete in PR #11; not merged to `master`. This report records source-backed behavior and tests, not a production deployment claim.

## 1. Result

Phase 21's acceptance trace is demonstrated in the PostgreSQL-backed `PrepWorkflowIT` test:

1. An authenticated test user has a persisted profile containing the skill `Java` and work history at `Café Systems`.
2. The real PREP HTTP endpoint generates a deterministic, job-specific CV from the stored profile records.
3. The returned CV contains those facts and emits provenance entries with `source_type=SKILL`, `claim=Java`, and `evidence_status=USER_VERIFIED`.
4. The stored validation report identifies `fact-validator-2` and passes for the supported checks.
5. The test then injects the unsupported sentence `I hold a PhD in Physics.` into the CV and validates it against the same persisted profile facts. The validator returns `UNSUPPORTED_QUALIFICATION` at `BLOCKER` severity and the report does not pass.

This is a database-backed integration test through the authenticated web layer, not a claim that an actual candidate's production profile was inspected. `USER_VERIFIED` means candidate-attested profile evidence; it is not third-party verification.

## 2. Source trace

### Profile storage and ownership

`ProfileController` resolves the authenticated user's ID from `AppUserDetails`, then resolves the caller's profile through `ProfileRepository.findByUserId`. Profile facts are loaded by profile ID from the structured skills, experiences, education, projects and certification records. The CV generation path does not accept another user's profile ID from the client as its source of truth.

### Tailored CV

`ResumeAtsIntelligenceService.loadSource` loads the candidate's structured profile records. Its CV renderer is deterministic: the job description may influence relevance ordering, keyword highlighting and gap reporting, but does not supply new candidate facts. The generated report contains evidence/provenance metadata and a validation report.

The validator version is included in the generation/input identity. The version was advanced from `fact-validator-1` to `fact-validator-2` because the validation semantics changed. The current-version rule is enforced at approval and again by `ApplyDocumentSelector`: a CV must have a current passing report, intact artifact/checksum and explicit owner review before it can enter an application package.

### Cover letter

`CoverLetterService` uses an LLM for prose generation, then validates the generated text against structured candidate facts. Its prompt now includes stored work-experience bullets, education details, project summaries/bullets, certifications and the candidate-provided professional summary. The summary is explicitly labelled candidate-provided, not independently verified. Certification credential IDs and structured work-authorisation data are not passed into the LLM prompt; work-authorisation statements are checked separately by the validator. Employer job-description content remains delimited and labelled untrusted input.

Cover-letter approval re-runs the current validator. PREP exposes a revalidation-and-approval action for older reports rather than treating an old approval as current.

## 3. Integrity issues corrected

- **Experience duration:** replaced earliest-start-year-to-present estimation with merged, non-overlapping documented work-month intervals. End dates and gaps are respected; skill-year evidence is no longer rounded up.
- **Quantitative evidence:** removed digits-only matching. A number is no longer treated as supported solely because the same digits occur with a different currency or unit.
- **Work eligibility:** explicit right-to-work and sponsorship claims are compared with the structured UK eligibility values. Contradictions become deterministic blockers; missing or ambiguous information is not silently treated as proof.
- **Employer identity:** only recorded work-experience employers substantiate prior-employment claims. The target hiring company, education institutions, projects and skills are not used as employment proof.
- **Validator migration:** `fact-validator-2` is required for CV approval and application packaging. Stale CVs are explicitly marked outdated in PREP; users must regenerate, inspect and approve a new CV version. The API rejects approval of a CV carrying an older validator report.
- **Cover-letter evidence context:** generation now receives structured profile details that were previously missing from the prompt, while minimising sensitive eligibility data passed to the LLM.

## 4. Verification

GitHub Actions run **#75**, commit `cd668c638f76eb4b24c8934271bcbe62d38f8022`, completed successfully on PR #11:

| Check | Result |
|---|---|
| Backend Surefire | 1,397 tests; 0 failures; 0 errors; 6 skipped |
| Backend Failsafe / integration | 272 tests; 0 failures; 0 errors; 25 skipped |
| `DocumentFactValidatorTest` | 13/13 passed |
| `CoverLetterReviewTest` | 8/8 passed |
| `PrepWorkflowIT` | 7/7 passed |
| Frontend type-check, tests and production build | Passed |
| Playwright browser worker | Passed |
| Cloudflare email worker tests | Passed |

The skipped tests are not counted as executed proof. In particular, `UserDataIsolationIT` was skipped by its environment assumption in this run, as were four live-ingestion tests. The run's green conclusion therefore does not prove those skipped scenarios.

## 5. Limits and remaining risks

- The validator is a bounded deterministic/heuristic check, not a semantic proof that every sentence is true. A passing report only means no unsupported claim of the checked kinds was detected.
- Employer-name and job-requirement findings remain heuristic warnings to avoid turning uncertain text extraction into false hard blockers.
- `USER_VERIFIED` is candidate-provided/attested evidence, not independently checked against employers, universities or issuing bodies.
- Work-eligibility data is currently UK-shaped. Explicit right-to-work and sponsorship contradictions are checked, but this does not establish permission for every country or interpret every natural-language immigration statement.
- Figure matching now preserves units/currency, but strict textual matching can still reject equivalent formatting (for example, `30 percent` vs `30%`) and substring boundary hardening remains a future improvement.
- Cover-letter prose remains LLM-generated and must be reviewed by the candidate. The validation layer does not make generated text inherently reliable.
- Older CV versions validated under `fact-validator-1` are intentionally stale. They must be regenerated and reviewed before application use. Older cover letters must be revalidated under current checks before they can be used.

## 6. PR and deployment state

The implementation is in [PR #11](https://github.com/kirankumarJADA/job-agent/pull/11), branch `phase21-candidate-knowledge-integrity`. All CI checks passed on the recorded head. PR #11 is not merged in this report; no production deployment or employer submission was performed as part of Phase 21.
