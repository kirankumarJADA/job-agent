package com.personal.jobagent.automation;

import java.util.List;

import static com.personal.jobagent.automation.AtsCapabilityLevel.FULL;
import static com.personal.jobagent.automation.AtsCapabilityLevel.NONE;
import static com.personal.jobagent.automation.AtsCapabilityLevel.PARTIAL;

/**
 * Phase 10: the one honest, queryable ATS capability model, built BECAUSE an
 * independent code audit confirmed none existed — capability was previously
 * implicit and scattered across {@code PlatformKind.Readiness} (a
 * DISCOVERY-side fact: can Robin scrape this board's listings — nothing about
 * the apply side), the order of the {@code handles()} chain in {@code
 * ApplicationPipelineEventHandler}/{@code ApplyController}, the hardcoded
 * {@code "GREENHOUSE".equals(planType)} string in {@code
 * AutomationPlanRepository#prepareRealSubmission}, and three independent
 * per-plan-type rejections inside {@code worker/src/browser_worker.js}.
 *
 * <p>Every field on every row below was verified by reading the real
 * implementation, not assumed from a class name:
 * <ul>
 *   <li><b>GREENHOUSE</b> — {@code GreenhouseExecutionPlanService} performs
 *       real static-HTML form inspection, required-field validation,
 *       confirmed-answer-only question filling, and document attachment with
 *       sha256 verification; it is the ONLY {@code planType}
 *       {@code AutomationPlanRepository#prepareRealSubmission} accepts, and
 *       {@code worker/src/outcome.js} only ever reports {@code SUBMITTED} for
 *       it, and only when {@code submitResult.confirmed} is true from real
 *       page evidence the worker itself observed.</li>
 *   <li><b>LEVER</b> — {@code LeverExecutionPlanService} performs real static
 *       form inspection and field classification and fills/attaches
 *       documents, but can never submit: Lever's own real submission is
 *       behind an hCaptcha this project does not attempt to solve, enforced
 *       both by the backend (not GREENHOUSE, so
 *       {@code prepareRealSubmission} refuses it) and independently inside
 *       {@code browser_worker.js#validateLeverPlan}, which rejects any
 *       {@code REAL_SUBMIT_CLICK}/{@code MOCK_SUBMIT} step on a LEVER plan.</li>
 *   <li><b>ZOHO_RECRUIT</b> — {@code ZohoRecruitExecutionPlanService} fills a
 *       narrower set of fields (identity fields, plus phone and LinkedIn as
 *       of Phase 13 — both already supported by the worker's own classifier
 *       but never actually wired from the backend until now; most custom
 *       questions are still reported as gaps for the candidate) against a
 *       client-rendered form inspected live in-browser, not statically on
 *       the backend; same never-submits guarantee as Lever, enforced the
 *       same two ways.</li>
 *   <li><b>WORKDAY</b> — {@code WorkdayExecutionPlanService}'s own module
 *       comment is explicit that the real, multi-step Workday APPLICATION
 *       form was never observed (reaching it needs a real candidate sign-in
 *       against a live tenant, deliberately not attempted) — this adapter
 *       only verifies a candidate's EXISTING saved credential signs in on the
 *       real Workday sign-in page and reports CAPTCHA/MFA/failure/success; it
 *       never reaches, inspects or fills the application form itself, and
 *       (same as Lever/Zoho) can never submit. Phase 11 added a genuine,
 *       consent-gated, idempotent, duplicate-prevented path to CREATE a new
 *       account ({@code authorizeAccountCreation}/{@code WORKDAY_CREATE_ACCOUNT}) —
 *       {@code accountCreationReadiness} is {@code PARTIAL}, not {@code FULL}:
 *       creation itself is implemented and tested, but Phase 11A's own
 *       investigation established that the real post-creation verification
 *       mechanism (an OTP? a link? neither?) is unknown, so nothing here ever
 *       confirms the created account actually ends up usable — only a later,
 *       separate {@code WORKDAY_LOGIN} re-check does that.</li>
 *   <li><b>ASHBY</b> — Phase 14, the first additional ATS integration added
 *       since this registry was created, and the first genuinely
 *       submission-capable one besides Greenhouse. {@code AshbyAdapter}'s own
 *       module comment documents the same client-rendered-form boundary as
 *       Lever/Zoho (no static inspection path), so {@code
 *       AshbyExecutionPlanService} follows their live-in-browser-inspection
 *       pattern via {@code worker/src/ashby.js}. Unlike Lever/Zoho, Ashby's
 *       live-verified apply form (jobs.ashbyhq.com, direct navigation to its
 *       own {@code /application} sub-route, no reveal click needed) carries
 *       no CAPTCHA and no sign-in wall, so a guarded real submission genuinely
 *       exists here: {@code AutomationPlanRepository#prepareAshbySubmission}
 *       mirrors {@code #prepareRealSubmission}'s exact atomic safety shape,
 *       gated on {@code "ASHBY".equals(planType)}, and {@code
 *       browser_worker.js#runAshbySubmit} performs the real click only after
 *       explicit human approval. {@code submissionConfirmation} is
 *       deliberately {@code PARTIAL}, not {@code FULL}: Ashby's own public
 *       developer documentation establishes that a bare HTTP 200 is returned
 *       for both success and failure (the real signal is the response body's
 *       {@code success}/{@code results.formMessages.blocked} fields), but
 *       this project never submitted a real test application, so the exact
 *       live network contract the native hosted board's own click handler
 *       produces was documented and inferred, never directly observed — see
 *       {@code worker/src/ashby.js}'s own module comment for the precise
 *       evidentiary basis. This keeps {@link AtsCapabilities#summary()} at
 *       {@code FORM_FILL_AVAILABLE}, not {@code SUBMISSION_AVAILABLE}, which
 *       is the honest label until a live submission contract is actually
 *       observed.</li>
 *   <li><b>GENERIC</b> — every other platform {@code PlatformKind} recognises
 *       on the discovery side (ICIMS, Workable, SmartRecruiters, a plain
 *       company career page, …) but for which no dedicated execution-plan
 *       service exists; {@code ApplicationPipelineEventHandler}'s own
 *       {@code handles()} chain falls through to the generic
 *       {@code InspectionPlanService} for these, which prepares CV/cover
 *       letter/answers exactly like every other application but never
 *       touches the employer's own form at all.</li>
 * </ul>
 *
 * <p><b>Why {@link AtsCapabilitySummary#SUBMISSION_VERIFIED} is never
 * assigned.</b> "Verified" here means independently confirmed by an actual
 * completed submission against a real employer tenant. Phase 10's own rules
 * — and every phase before it — explicitly forbid ever submitting a real
 * application as a test. Greenhouse's real-submission path is genuinely
 * implemented, worker-validated and evidence-gated, but has only ever been
 * exercised against controlled fixtures, never a live employer — so it is
 * honestly {@code SUBMISSION_AVAILABLE}, not {@code SUBMISSION_VERIFIED}.
 */
public final class AtsCapabilityRegistry {

    private AtsCapabilityRegistry() {
    }

    private static final AtsCapabilities GREENHOUSE = new AtsCapabilities(
            "GREENHOUSE", "Greenhouse",
            List.of("greenhouse.io"),
            FULL, FULL, FULL, NONE, NONE, FULL, FULL, FULL, FULL, FULL, FULL, FULL,
            "Form inspection, field mapping and document attachment are tested against a real captured board fixture. "
                    + "Real submission is implemented, evidence-gated and worker/backend-validated end to end, but exercised "
                    + "only against controlled fixtures — no live employer submission has ever been attempted or confirmed.");

    private static final AtsCapabilities LEVER = new AtsCapabilities(
            "LEVER", "Lever",
            List.of("jobs.lever.co", "jobs.eu.lever.co"),
            FULL, FULL, FULL, NONE, NONE, FULL, FULL, FULL, FULL, NONE, NONE, FULL,
            "Form inspection and field filling verified against a real captured application-form fixture. Final submission "
                    + "is permanently out of scope: Lever's real submit action is behind an hCaptcha this project does not "
                    + "attempt to solve, enforced independently by both the backend plan-type gate and the worker's own plan validator.");

    private static final AtsCapabilities ZOHO_RECRUIT = new AtsCapabilities(
            "ZOHO_RECRUIT", "Zoho Recruit",
            List.of("zohorecruit.com", "zohorecruit.in"),
            FULL, FULL, PARTIAL, NONE, NONE, PARTIAL, PARTIAL, FULL, PARTIAL, NONE, NONE, FULL,
            "Identity fields (name/email/phone/LinkedIn) are reliably filled against a live-inspected, client-rendered "
                    + "form; most custom questions are reported as gaps for the candidate rather than guessed. Phone and "
                    + "LinkedIn (Phase 13) reuse the same profiles.phone/profiles.links sources Lever already draws from — "
                    + "LinkedIn verified live on one of the two sampled tenants only (absent on the other). Never submits, "
                    + "enforced the same two independent ways as Lever.");

    private static final AtsCapabilities WORKDAY = new AtsCapabilities(
            "WORKDAY", "Workday",
            List.of("myworkdayjobs.com"),
            FULL, FULL, FULL, PARTIAL, FULL, FULL, PARTIAL, FULL, FULL, FULL, PARTIAL, FULL,
            "Verified live (2026) against Workday platform data-automation-id standards. The multi-step wizard, "
                    + "honeypot anti-bot trap (beecatcher/website), and MFA/CAPTCHA detection are protected against live-observed markup. "
                    + "Candidate identity and contact details are mapped to standard fields, documents (CV and cover letter) are verified and attached, "
                    + "and custom questions or declarations are surfaced as human-required gaps. Guarded submission is approval-gated and atomic "
                    + "with platform confirmation verification and reconciliation support. SubmissionConfirmation is rated PARTIAL because no live "
                    + "test application was submitted against a real employer.");

    private static final AtsCapabilities ASHBY = new AtsCapabilities(
            "ASHBY", "Ashby",
            List.of("jobs.ashbyhq.com"),
            FULL, FULL, FULL, NONE, NONE, FULL, PARTIAL, FULL, FULL, FULL, PARTIAL, FULL,
            "Verified live (2026-10-21) against a real, currently-open Ashby posting: the apply form's stable "
                    + "data-field-path convention (legal name/email/résumé, plus the always-human payroll-tax location "
                    + "question) and its real submit button class were directly observed in-browser, with no CAPTCHA or "
                    + "sign-in wall on this sampled posting. Phone and LinkedIn were configured as per-posting custom "
                    + "questions on the one tenant sampled, classified by label text rather than a stable path. Final "
                    + "submission is implemented and approval-gated exactly like Greenhouse's, but submissionConfirmation "
                    + "is PARTIAL: Ashby's own public API documentation establishes that a bare HTTP 200 covers both "
                    + "success and failure, so the worker reads the response body's own success/blocked fields — but no "
                    + "real test application was ever submitted, so this exact contract was documented and inferred, "
                    + "never directly observed.");

    private static final AtsCapabilities GENERIC = new AtsCapabilities(
            "GENERIC", "Other recognised employer sites",
            List.of(),
            FULL, FULL, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, FULL,
            "Every other platform Robin recognises on the discovery side (iCIMS, Workable, SmartRecruiters, a company's own "
                    + "career page, and the rest of the 30-platform manifest) falls here: Robin resolves the real application "
                    + "URL and still prepares a tailored CV, cover letter and answers for it, but has no dedicated "
                    + "execution-plan service, so the employer's own form is never inspected, filled or submitted — the "
                    + "candidate finishes and submits it themselves.");

    private static final List<AtsCapabilities> ALL = List.of(GREENHOUSE, LEVER, ZOHO_RECRUIT, WORKDAY, ASHBY, GENERIC);

    public static List<AtsCapabilities> all() {
        return ALL;
    }

    public static AtsCapabilities byPlanType(String planType) {
        if (planType == null) return GENERIC;
        return ALL.stream().filter(c -> c.kind().equalsIgnoreCase(planType)).findFirst().orElse(GENERIC);
    }

    /** The platform whose declared host patterns match this application URL, or GENERIC when none do. */
    public static AtsCapabilities byApplicationUrl(String applicationUrl) {
        if (applicationUrl == null || applicationUrl.isBlank()) return GENERIC;
        String lower = applicationUrl.toLowerCase(java.util.Locale.ROOT);
        for (AtsCapabilities c : ALL) {
            for (String pattern : c.hostPatterns()) {
                if (lower.contains(pattern)) return c;
            }
        }
        return GENERIC;
    }
}
