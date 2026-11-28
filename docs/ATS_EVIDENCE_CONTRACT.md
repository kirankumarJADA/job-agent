# Reusable ATS Submission Evidence Contract (`SUBMISSION_EVIDENCE`)

This document defines the canonical browser-to-backend submission evidence contract introduced in Wave 3 Sprint 2.3 (implemented first for `GREENHOUSE`). Later ATS adapters (`WORKDAY`, `ASHBY`, and future confirmation-capable adapters) MUST produce the same structure without introducing adapter-specific trust bypasses.

## 1. Boundary & Authority

- **Worker responsibility (untrusted observation channel):**
  Execute an explicitly authorized submission step, verify attempt-scoped submission request/response + confirmation page DOM signals, extract only genuinely observed confirmation data, redact credentials/cookies/tokens/form values, enforce bounded payload limits, and emit a `SUBMISSION_EVIDENCE` entry.
- **Backend responsibility (sole trust & verification authority):**
  Derive worker principal (`worker` bearer authentication), candidate plan ownership, human submit-approval attestation (`audit_logs`), active candidate-owned sandbox registration (`candidate_sandbox_registrations`), server-side SHA-256 digest (`SubmissionEvidenceRepository`), AES-256-GCM ciphertext at rest, durable verified receipt (`ReceiptCaptureService`), and continuous read/tracking revalidation (`ApplicationReceiptService`, `TrackRepository`).

The worker MUST NEVER send `profileId`, `approvedBy`, `permittedDomain`, `evidenceProvenance`, `sandboxPosting`, or `candidateAuthorizedSandbox`. `outcome.js` and `SubmissionCompletionService` strip/ignore any caller-asserted trust fields.

## 2. Worker Outcome Payload Shape

When `computePlanOutcome` resolves a submission attempt to `outcome: 'SUBMITTED'`, `result` MUST contain exactly one entry:

```json
{
  "type": "SUBMISSION_EVIDENCE",
  "planType": "GREENHOUSE",
  "category": "CONFIRMED",
  "confirmed": true,
  "evidence": "Human-readable verification summary (<= 4000 chars)",
  "confirmationNumber": "Optional — ONLY when genuinely observed on response/DOM",
  "signals": [
    "GREENHOUSE_SUBMISSION_RESPONSE_2XX",
    "GREENHOUSE_CONFIRMATION_URL_MATCHED",
    "GREENHOUSE_CONFIRMATION_CONTAINER_PRESENT",
    "EMPLOYER_CONFIRMATION_ID_OBSERVED"
  ],
  "rawEvidence": "<canonical JSON string suitable for server SHA-256 & AES-GCM encryption>"
}
```

## 3. Canonical `rawEvidence` Schema (`robin-submission-evidence-v1`)

```json
{
  "atsPlatform": "GREENHOUSE",
  "attemptCorrelation": {
    "applicationId": "...",
    "jobId": "...",
    "planId": "..."
  },
  "confirmationContainerHtml": "<sanitised bounded confirmation container DOM only>",
  "confirmationNumber": "GH-..." ,
  "confirmationPageUrl": "https://boards.greenhouse.io/{board}/jobs/{id}/confirmation",
  "confirmationText": "<bounded visible confirmation container text>",
  "observedAt": "2026-11-25T00:00:00.000Z",
  "request": {
    "method": "POST",
    "status": 200,
    "url": "https://boards.greenhouse.io/{board}/jobs/{id}"
  },
  "schemaVersion": "robin-submission-evidence-v1",
  "signals": ["..."]
}
```

## 4. Non-Negotiable Safety Invariants for All Adapters

1. **Attempt correlation:** Network listeners MUST be scoped to the single click attempt and torn down immediately; a previous attempt's request/response may never satisfy a new attempt.
2. **Container-only DOM capture:** Capture only the platform confirmation container (e.g. `.confirmation`), stripped of `<script>`, `<style>`, event handlers, `<input>`/`<textarea>`/`<select>` applicant data, cookies, and tokens; never dump the entire applicant `<body>`.
3. **Zero fabricated identifiers:** Never invent a confirmation ID and never substitute `jobId`, `applicationId`, `requisitionId` (`REQ-*`, `REQUISITION-*`, `JOB-REQ-*`, `RECEIPT-*`), or test/fixture prefixes (`MOCK-*`, `TEST-*`, `SAMPLE-*`, `SIM-*`, `FIXTURE-*`, `SYNTHETIC-*`). If no genuine confirmation ID is present, omit `confirmationNumber` so the backend stores it unverified until reconciled or corroborated.
4. **Honest fail-closed ambiguity & crash recovery:** Persist `REAL_SUBMIT_ATTEMPTING` (or adapter equivalent) durably before the click. Crashes before or after click without completed durable persistence MUST resolve to `PRIOR_ATTEMPT_UNVERIFIED` / `RECONCILIATION_REQUIRED` and never trigger a second click.
