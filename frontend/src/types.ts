export interface User {
  id: string;
  email: string;
  displayName: string;
  role?: string;
  isOperator?: boolean;
}

/**
 * A posting as the CANDIDATE sees it (GET /jobs, GET /jobs/{id}).
 *
 * Source hiding (Phase 8.2.5): the API deliberately omits which website, ATS
 * platform or aggregator surfaced the posting — no source id/name/kind, no
 * source external id, no discovery (canonical) URL. Do not add those fields
 * back here; provenance belongs to the operational Sources view only.
 */
export interface Job {
  id: string;
  company_id?: string;
  company_name_raw?: string;
  title: string;
  location_raw?: string;
  city?: string;
  country?: string;
  remote_type?: 'REMOTE' | 'HYBRID' | 'ONSITE' | 'UNKNOWN';
  employment_type?: string;
  experience_level?: string;
  salary_min?: number;
  salary_max?: number;
  salary_currency?: string;
  salary_period?: string;
  description_text: string;
  skills_extracted: string[];
  /** The employer's own application page (never an aggregator URL). */
  application_url?: string;
  posted_at?: string;
  posted_date_source?: 'EXPLICIT' | 'INFERRED' | 'UNKNOWN';
  status: 'DISCOVERED' | 'FILTERED_OUT' | 'ANALYSED' | 'SCORED' | 'DECIDED' | 'ARCHIVED' | 'PIPELINE_ERROR';
  first_seen_at: string;
  last_seen_at: string;
  /** Not seen by discovery for 30+ days (same rule the review queue uses to block approval). */
  stale?: boolean;
  /** Soft-deleted from the catalogue; only ever true on the detail endpoint. */
  removed?: boolean;
  /** The signed-in candidate's own match; null when this candidate has not scored the posting. */
  match_score?: number | null;
  match_recommendation?: 'APPLY' | 'REVIEW' | 'SKIP' | null;
  match_breakdown?: MatchBreakdown | null;
}

export interface MatchBreakdown {
  skill_overlap?: number;
  remote_fit?: number;
  salary_fit?: number;
  // Robin x Tsenta continuation: the three categories the seven-category
  // scoringWeights contract added beyond the original three factors.
  // Each is 0-100 whenever reported, but only actually weighed into
  // `score` when `applicable[<category>]` is true — see
  // JobMatchService#evaluateMatch: an unset candidate preference
  // excludes a category entirely rather than scoring it neutral.
  experience_fit?: number;
  visa_fit?: number;
  location_fit?: number;
  weights?: Record<string, number>;
  applicable?: Record<string, boolean>;
  thresholds?: { apply: number; review: number };
  matched_skills?: string[];
  missing_skills?: string[];
  highlights?: string[];
  concerns?: string[];
  why?: string;
  decision?: string;
}

export interface JobDetailResponse {
  job: Job;
  analysis: {
    sponsorship?: {
      status: string;
      confidence: number;
      reason: string;
      evidence: Array<{ type: string; snippet?: string; locator?: string }>;
    };
    summary?: string;
    match_explanation?: string;
    skills_required?: Record<string, string[]>;
  } | null;
  // The CALLER's own match decision (job_matches), including the persisted
  // explanation of why the score came out the way it did.
  match: {
    score: number;
    recommendation: 'APPLY' | 'REVIEW' | 'SKIP';
    breakdown: MatchBreakdown | null;
    scored_at: string;
  } | null;
  decision_trace: Array<{ step: string; outcome: string; reason?: string }>;
}

export interface WorkEligibility {
  right_to_work_uk: boolean;
  visa_status: string;
}

/** READ-ONLY execution package: Robin's per-field form decision record. */
export interface AutomationPackageView {
  planId: string;
  applicationId: string;
  jobId: string;
  expectedUrl: string;
  candidate: { email: string; fullName: string; phone: string; location: string };
  cv?: { versionId: string; sha256: string; fileName: string } | null;
  coverLetter?: { versionId: string; sha256: string; fileName: string } | null;
  artifacts: Array<{ kind: string; versionId: string; sha256: string; fileName: string }>;
  answers: Array<{ questionText: string; answerText: string; source: string }>;
  fields: Array<{
    key: string | null;
    label: string;
    htmlType: string;
    required: boolean;
    classification: string;
    valueSource: string;
    value: string;
    reason: string;
  }>;
  human: Array<{ key: string; label: string; classification: string; reason: string }>;
  unsupported: Array<{ key: string; label: string; classification: string; reason: string }>;
  requiredGaps: Array<{ key: string; label: string; classification: string; reason: string }>;
  safetyContract: string;
}

/**
 * Robin x Tsenta continuation: one field Robin either filled or still needs
 * from the candidate on a Zoho Recruit application form, as reported by
 * `worker/src/zoho_recruit.js`'s classification — `category` is one of
 * `ZohoFieldCategory` (IDENTITY_FIRST_NAME, RESUME_UPLOAD, REQUIRES_HUMAN, …).
 */
export interface ZohoRecruitField {
  label: string | null;
  category: string;
  required?: boolean;
}

/**
 * Honest document-upload state (Robin x Tsenta hardening) — mirrors
 * `worker/src/zoho_recruit.js`'s `ZohoDocumentUploadState`. A successful
 * `setInputFiles` call only proves the file was selected into the browser's
 * own form; it is never proof the employer's server received or stored it,
 * and this adapter never submits, so `ATTACHED` — not `CONFIRMED` — is the
 * actual terminal state every successful attempt reaches today. `CONFIRMED`
 * is reserved for a future, separately verified employer-side signal; no
 * code in this adapter produces it yet.
 */
export type ZohoDocumentUploadState = 'NO_FIELD' | 'NO_ARTIFACT' | 'REJECTED' | 'ATTACHED' | 'CONFIRMED';

/**
 * The structured outcome a ZOHO_RECRUIT plan's one real worker step reports
 * back, persisted verbatim in the automation plan's `result` column (see
 * `AutomationController#complete`). This plan type never reaches SUBMITTED —
 * every successful run ends AWAITING_APPROVAL, with this payload explaining
 * exactly what Robin filled, what still needs the candidate, and why.
 */
export interface ZohoRecruitOutcome {
  type: 'ZOHO_RECRUIT_OUTCOME';
  planType: 'ZOHO_RECRUIT';
  filled: ZohoRecruitField[];
  requiredGaps: ZohoRecruitField[];
  fields?: ZohoRecruitField[];
  resumeUploadState: ZohoDocumentUploadState;
  resumeUploadReason?: string | null;
  coverLetterUploadState?: ZohoDocumentUploadState;
  coverLetterUploadReason?: string | null;
  formFound?: boolean;
  applicationUrl?: string | null;
}

/**
 * Phase 6: one field Robin either filled or still needs from the candidate
 * on a Lever application form, as reported by the backend-computed field
 * list (`LeverFieldMapper`/`LeverExecutionPlanService`) and executed by
 * `worker/src/lever.js`.
 */
export interface LeverField {
  key: string;
  label: string | null;
}

/**
 * The structured outcome a LEVER plan's one real worker step reports back,
 * persisted verbatim in the automation plan's `result` column. Mirrors
 * {@link ZohoRecruitOutcome}'s shape and the same honesty rule: `ATTACHED`
 * only proves the résumé file was selected into the browser's own form, not
 * that the employer's server received it — this adapter never submits, so
 * every successful run ends AWAITING_APPROVAL, never SUBMITTED.
 */
export interface LeverOutcome {
  type: 'LEVER_OUTCOME';
  planType: 'LEVER';
  filled: LeverField[];
  requiredGaps: LeverField[];
  fields?: Array<{ key: string; label: string | null; classification: string; required: boolean }>;
  resumeUploadState: 'NO_FIELD' | 'NO_ARTIFACT' | 'REJECTED' | 'ATTACHED';
  resumeUploadReason?: string | null;
  formFound?: boolean;
  applicationUrl?: string | null;
}

/**
 * Persisted alongside a FAILED/BLOCKED_ANTI_BOT outcome (see `worker.js`'s
 * poll-loop catch block) so the review panel can show the candidate the real
 * reason preparation stopped — a lost lease, a CAPTCHA, a thrown error —
 * instead of a bare "failed" with nothing to act on.
 */
export interface PlanErrorResult {
  type: 'PLAN_ERROR';
  outcome: 'FAILED' | 'BLOCKED_ANTI_BOT';
  reason?: string | null;
  message: string;
}

/**
 * Phase 7: the structured outcome a WORKDAY plan's one real worker step
 * (`WORKDAY_LOGIN`) reports back, persisted verbatim in the automation
 * plan's `result` column (see `outcome.js`'s WORKDAY branch). This is
 * deliberately NOT a form-fill or submission outcome — Workday's real,
 * multi-step application form was never observed or implemented in this
 * phase; the only thing this plan type ever proves is whether the
 * candidate's saved credential signs in on the employer's real Workday
 * sign-in page, so the candidate knows before manually finishing the
 * application themselves. `CREDENTIAL_REQUIRED` means no saved credential
 * existed at all, so sign-in was never attempted.
 *
 * <p>Phase 11: `accountCreationAttempted` marks the other plan shape this
 * same outcome type now also carries — a candidate-authorized attempt to
 * create a NEW Workday account (`POST /apply/applications/{id}/workday-account`),
 * never a login check. `ACCOUNT_CREATED`/`ACCOUNT_CREATE_FAILED`/
 * `TERMS_NOT_AUTHORIZED` only ever appear on that plan shape; a single plan
 * is always one or the other, never both.
 */
export interface WorkdayOutcome {
  type: 'WORKDAY_OUTCOME';
  planType: 'WORKDAY';
  credentialSaved: boolean;
  accountCreationAttempted?: boolean;
  loginStatus: 'CREDENTIAL_REQUIRED' | 'LOGIN_SUCCEEDED' | 'LOGIN_FAILED' | 'FORM_NOT_FOUND'
    | 'ACCOUNT_CREATED' | 'ACCOUNT_CREATE_FAILED' | 'TERMS_NOT_AUTHORIZED';
  reason?: string | null;
  applicationUrl?: string | null;
}

/**
 * Phase 11A: GET /apply/applications/{id}/workday-account/verification-status
 * — a read-only poll. Never carries an OTP or a link, by construction: the
 * backend composes this from the account-creation plan's own result plus a
 * non-consuming mailbox probe, and this type has no field for either secret.
 * `provider`/`receivedAt` are only ever set when `status` is
 * `VERIFICATION_MESSAGE_MATCHED`.
 */
export interface WorkdayAccountVerificationStatus {
  status: 'ACCOUNT_CREATION_REQUIRED' | 'ACCOUNT_CREATION_IN_PROGRESS' | 'TERMS_NOT_AUTHORIZED'
    | 'ACCOUNT_CREATE_FAILED' | 'FORM_NOT_FOUND' | 'VERIFICATION_MESSAGE_PENDING'
    | 'VERIFICATION_MESSAGE_MATCHED' | 'HUMAN_ASSISTANCE_REQUIRED' | 'VERIFICATION_FAILED_OR_EXPIRED';
  provider?: string | null;
  receivedAt?: string | null;
  detail?: string | null;
}

/**
 * Phase 14: the structured outcome an ASHBY plan's ASHBY_APPLY step reports
 * back, persisted verbatim in the automation plan's `result` column (see
 * `outcome.js`'s ASHBY branch) — mirrors {@link ZohoRecruitOutcome}'s shape
 * exactly (no cover letter: Ashby's one live-sampled posting has no matching
 * field — see `AshbyExecutionPlanService`'s own module comment). Unlike
 * Zoho/Lever, an ASHBY plan CAN reach SUBMITTED — but only once a separate,
 * explicit `ASHBY_SUBMIT_CLICK` step (queued via the plan's existing
 * approve-submit / prepare-submit actions, never from this outcome alone)
 * has run and the worker's own evidence-based confirmation says so.
 */
export interface AshbyOutcome {
  type: 'ASHBY_OUTCOME';
  planType: 'ASHBY';
  filled: ZohoRecruitField[];
  requiredGaps: ZohoRecruitField[];
  fields?: ZohoRecruitField[];
  resumeUploadState: ZohoDocumentUploadState;
  resumeUploadReason?: string | null;
  formFound?: boolean;
  applicationUrl?: string | null;
}

export interface WorkdayField {
  label: string;
  category: 'STANDARD' | 'OPTIONAL_STANDARD' | 'EXPERIENCE' | 'DECLARATION' | 'CUSTOM' | 'REQUIRES_HUMAN' | 'SENSITIVE' | 'UNKNOWN';
  value?: string | null;
  required?: boolean;
}

/**
 * Phase 19: the structured outcome a WORKDAY plan's WORKDAY_APPLY step reports
 * back, persisted verbatim in the automation plan's `result` column (see
 * `outcome.js`'s WORKDAY branch).
 */
export interface WorkdayApplicationOutcome {
  type: 'WORKDAY_APPLICATION_OUTCOME';
  planType: 'WORKDAY';
  currentStep?: 'MY_INFORMATION' | 'MY_EXPERIENCE' | 'APPLICATION_QUESTIONS' | 'VOLUNTARY_DISCLOSURES' | 'REVIEW' | 'UNKNOWN';
  filled: WorkdayField[];
  requiredGaps: WorkdayField[];
  fields?: WorkdayField[];
  resumeUploadState: 'ATTACHED' | 'FAILED' | 'MISSING' | 'NOT_REQUESTED';
  resumeUploadReason?: string | null;
  coverLetterUploadState?: 'ATTACHED' | 'FAILED' | 'MISSING' | 'NOT_REQUESTED';
  coverLetterUploadReason?: string | null;
  formFound?: boolean;
  applicationUrl?: string | null;
  credentialSaved?: boolean;
}

/**
 * Phase 15 & 19: the structured outcome an automation plan reports back when a
 * real submit click could not be confirmed, persisted verbatim in the
 * automation plan's `result` column.
 */
export interface SubmissionReconciliationResult {
  type: 'SUBMISSION_RECONCILIATION';
  planType: 'GREENHOUSE' | 'ASHBY' | 'WORKDAY';
  category: string;
  evidence: string;
}

/** GET /automation/plans/{id} — the fields the Zoho Recruit / Lever / Workday / Ashby review panels read. */
export interface AutomationPlanView {
  id: string;
  applicationId: string;
  targetUrl: string;
  status: string;
  plan?: { planType?: string | null } | null;
  result?: (ZohoRecruitOutcome | LeverOutcome | WorkdayOutcome | WorkdayApplicationOutcome | AshbyOutcome | PlanErrorResult | SubmissionReconciliationResult)[] | null;
  submitApproved: boolean;
  heartbeatAt?: string | null;
  updatedAt: string;
}

export interface ApplicationTimelineEvent {
  id: string;
  type: string;
  payload: Record<string, unknown>;
  actor: string;
  occurred_at: string;
}

export interface WorkExperience {
  id: string;
  company: string;
  title: string;
  start_month: string;
  end_month?: string | null;
  location?: string;
  bullets: Array<{ id?: string; text: string }>;
  sort_order: number;
}

export interface Skill {
  id: string;
  name: string;
  category?: string;
  mastery: number; // 1-5
  years?: number;
}

export interface Education {
  id: string;
  institution: string;
  qualification: string;
  field?: string;
  start_year?: number;
  end_year?: number;
  grade?: string;
}

export interface Project {
  id: string;
  name: string;
  summary?: string;
  url?: string;
  bullets: Array<{ id?: string; text: string }>;
}

export interface Certification {
  id: string;
  name: string;
  issuer?: string;
  issued_on?: string;
  credential_id?: string;
}

export interface Profile {
  id: string;
  user_id: string;
  headline?: string;
  phone?: string;
  location?: string;
  work_eligibility: WorkEligibility;
  career_goals: { summary?: string; [key: string]: unknown };
  professional_summary?: string;
  links?: Record<string, unknown>;
  master_revision: number;
  setup_status: 'INCOMPLETE' | 'READY';
  experiences: WorkExperience[];
  skills: Skill[];
  education: Education[];
  projects: Project[];
  certifications: Certification[];
}

export interface ScoringWeights {
  skill: number;
  experience: number;
  visa: number;
  location: number;
  salary: number;
  career: number;
  difficulty: number;
}

// Matches the API's actual JSON casing (Jackson default camelCase, confirmed
// against the live endpoint). The previous snake_case shape never matched the
// wire format, so most saved values silently fell back to UI defaults on load.
export interface PreferenceSet {
  id: string;
  profileId: string;
  titles: string[];
  keywordsInclude: string[];
  keywordsExclude: string[];
  requiredSkills: string[];
  locationsAllowed: string[];
  remoteTypes: string[];
  employmentTypes: string[];
  experienceLevels?: string[];
  salaryMinGbp?: number;
  sponsorshipPolicy: 'SPONSORSHIP_REQUIRED' | 'SPONSORSHIP_PREFERRED' | 'SPONSORSHIP_NOT_REQUIRED' | 'SHOW_ALL';
  applicationMode: ApplicationMode;
  scoringWeights: ScoringWeights;
  /**
   * Free-form extension bag (PUT full-replacement semantics — see
   * PreferenceSetRecord's own backend doc comment). Phase 9 uses exactly one
   * key here, `countries` (ISO-3166-1 alpha-2 codes), to drive WHICH
   * region-scoped discovery sources `POST /discovery/run-for-me` selects —
   * never used for post-discovery job filtering (that stays `locationsAllowed`).
   */
  extraFilters?: { countries?: string[] } & Record<string, unknown>;
  isActive: boolean;
}

/**
 * GET /api/v1/ats-capabilities — Phase 10's honest, backend-verified ATS
 * capability model (see AtsCapabilityRegistry's own doc comment). Static
 * product facts, not per-candidate data. The frontend uses `hostPatterns` to
 * classify a job's own `application_url` and `summary` to pick a truthful,
 * non-technical label — never inventing its own classification scheme.
 */
export interface AtsCapabilityRow {
  kind: string;
  displayName: string;
  hostPatterns: string[];
  summary: 'DISCOVERY_ONLY' | 'MANUAL_APPLICATION_REQUIRED' | 'PREPARATION_AVAILABLE'
    | 'SIGN_IN_READINESS_ONLY' | 'FORM_FILL_AVAILABLE' | 'SUBMISSION_AVAILABLE' | 'SUBMISSION_VERIFIED';
  dimensions: Record<string, string>;
  verificationNote: string;
}

/** POST /api/v1/discovery/run-for-me — Phase 9's preference-driven, multi-source discovery run. */
export interface DiscoveryRunSummary {
  quotaExceeded: boolean;
  sourcesQueried: number;
  sourcesSucceeded: number;
  sourcesFailed: number;
  sourcesRequireConfiguration: number;
  newJobs: number;
  updatedJobs: number;
  totalListed: number;
  notes: string[];
}

// The decision engine's application modes (mirrors ApplicationDecisionService).
export type ApplicationMode = 'MANUAL' | 'ASSISTED' | 'CONTROLLED_AUTO';

/**
 * How well the backend could resolve the owner's auto-approval rule (Phase 7.3).
 *
 *  - CONFIGURED: a valid saved rule was loaded.
 *  - ABSENT: no custom rule exists, so the decision mode's defaults apply.
 *  - UNREADABLE: the rule could not be retrieved or validated. The backend
 *    fails closed: automatic approval is paused and matches wait for review.
 *    This is returned as HTTP 503, never as a successful response, so a
 *    database or validation error can never be mistaken for "no rule".
 */
export type ApprovalRuleAvailability = 'CONFIGURED' | 'ABSENT' | 'UNREADABLE';

// Per-user auto-approval rule from GET/PUT /api/v1/approval-rules (Phase 7).
// The rule can toggle automatic approval and set a minimum score threshold;
// it can never bypass hard stops, quotas, or safety gates. Only returned for
// the CONFIGURED and ABSENT states; an unreadable rule returns 503 instead.
export interface ApprovalRule {
  availability: ApprovalRuleAvailability;
  configured: boolean;
  autoApproveEnabled: boolean;
  minScore: number;
  applicationMode: ApplicationMode;
  /** ASSISTED-mode score floor the backend applies when no rule is saved. */
  assistedFloor: number;
}

// GET /api/v1/approval-rules/activity (Robin x Tsenta continuation): the
// decision engine's own persisted decisions, aggregated — never a separate
// tracking mechanism, so it can never disagree with what actually happened.
export interface ApprovalActivityWindow {
  considered: number;
  byDecision: Record<string, number>;
}

export interface ApprovalActivity {
  today: ApprovalActivityWindow;
  last7Days: ApprovalActivityWindow;
  dailyApplicationQuota: { used: number; limit: number; remaining: number };
}

// GET/PUT /api/v1/auto-apply/settings (Phase 5, Robin x Tsenta continuation):
// the explicit, bounded Auto Apply BATCH feature's own candidate-scoped
// configuration. Separate from ApprovalRule/applicationMode above, which
// govern the continuous background decision engine — enabling this setting
// never starts anything by itself; a run is always an explicit POST.
export interface AutoApplySettings {
  enabled: boolean;
  configured: boolean;
  minScore: number;
  dailyCap: number;
  maxBatchSize: number;
  remoteType: string | null;
  location: string | null;
  employmentType: string | null;
  salaryMin: number | null;
}

export type AutoApplyPredictedOutcome =
  | 'WOULD_PREPARE'
  | 'SKIPPED_THRESHOLD'
  | 'SKIPPED_DUPLICATE'
  | 'QUOTA_LIMITED';

export interface AutoApplyPreviewItem {
  jobId: string;
  jobTitle: string;
  companyName: string;
  matchScore: number | null;
  predictedOutcome: AutoApplyPredictedOutcome;
  detail: string | null;
}

// GET /api/v1/auto-apply/preview: a read-only prediction. Nothing is created
// and no quota is consumed by reading this.
export interface AutoApplyPreview {
  jobsConsidered: number;
  jobsEligible: number;
  items: AutoApplyPreviewItem[];
}

export type AutoApplyRunOutcome =
  | 'PREPARED'
  | 'SKIPPED_DUPLICATE'
  | 'SKIPPED_THRESHOLD'
  | 'SKIPPED_MISSING_PROFILE'
  | 'SKIPPED_UNSUPPORTED_FLOW'
  | 'QUOTA_LIMITED'
  | 'FAILED';

// One row of GET/POST /api/v1/auto-apply/runs — a bounded, auditable batch
// run. "prepared" means an application was created and handed to the
// existing asynchronous CV/cover-letter/answer preparation pipeline; it is
// never evidence of submission.
export interface AutoApplyRunSummary {
  id: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  jobsConsidered: number;
  jobsEligible: number;
  prepared: number;
  skippedDuplicate: number;
  skippedThreshold: number;
  skippedMissingProfile: number;
  skippedUnsupportedFlow: number;
  quotaLimited: number;
  failures: number;
  startedAt: string;
  completedAt: string | null;
  error: string | null;
}

export interface AutoApplyRunItem {
  jobId: string;
  jobTitle: string;
  companyName: string;
  applicationId: string | null;
  matchScore: number | null;
  outcome: AutoApplyRunOutcome;
  detail: string | null;
  createdAt: string;
}

export interface AutoApplyRunDetail extends AutoApplyRunSummary {
  items: AutoApplyRunItem[];
}

// Owner-scoped application row from GET /api/v1/applications (camelCase wire
// format). Match fields come from the candidate's own job_matches decision.
export interface ApplicationSummary {
  id: string;
  jobId: string;
  status: string;
  mode: string;
  createdAt: string;
  updatedAt: string;
  jobTitle: string;
  company: string;
  jobLocation?: string | null;
  matchScore?: number | null;
  matchRecommendation?: string | null;
  planId?: string | null;
  planStatus?: string | null;
  /**
   * The automation plan's ATS adapter ("GREENHOUSE", "ZOHO_RECRUIT", "INSPECTION",
   * or null when none exists yet). Determines which review UI applies — a
   * ZOHO_RECRUIT plan never offers "Approve for submission" (it has no real
   * submission capability at all), unlike a GREENHOUSE plan in the same
   * AWAITING_APPROVAL status.
   */
  planType?: string | null;
  planSubmitApproved?: boolean | null;
  planHeartbeatAt?: string | null;
  planUpdatedAt?: string | null;
  /**
   * TRACK (Phase 8.3): the status Robin derives from evidence. `status` above
   * stays Robin's internal workflow status; this is what the candidate sees.
   */
  track?: TrackSummary | null;
}

export interface LlmModel {
  id: string;
  provider_id: string;
  model_key: string;
  display_name?: string;
  context_window?: number;
  enabled: boolean;
  notes?: Record<string, unknown>;
}

export interface RoutingPolicy {
  task_type: string;
  primary_model_id?: string;
  fallback_model_ids: string[];
  basis: 'BENCHMARK' | 'MANUAL' | 'DEFAULT';
  based_on_run_id?: string;
  rationale?: string;
  updated_at: string;
}

export interface BenchmarkRun {
  id: string;
  suite: string;
  task_type: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  started_at: string;
  finished_at?: string;
  notes?: string;
}

export interface BenchmarkResult {
  id: string;
  model_id: string;
  case_index: number;
  passed: boolean;
  score: number;
  metrics: {
    latency_ms: number;
    json_valid: boolean;
    retries: number;
  };
}

export interface JobSource {
  id: string;
  kind: string;
  org_identifier: string;
  display_name: string;
  policy: string;
  schedule_cron?: string | null;
  rate_limit_per_min: number;
  enabled: boolean;
  failure_streak: number;
  last_run_at?: string;
  last_attempted_at?: string | null;
  last_success_at?: string | null;
  /** JSON text of the last run's counters (listed/created/updated/…, duration, failure category). */
  last_run?: string | Record<string, unknown> | null;
  /** JSON text of the recorded health ({status, checked_at, detail, error, failure_streak, circuit}). */
  health?: string | Record<string, unknown> | null;
}

/** GET /sources/coverage — one row per platform (operations view only). */
export interface PlatformCoverage {
  kind: string;
  name: string;
  group: 'A' | 'B' | 'C' | 'AGGREGATOR';
  aliases: string[];
  readiness: 'LIVE_VALIDATED' | 'UNVERIFIED' | 'BLOCKED';
  adapter: boolean;
  status: 'SUPPORTED' | 'NEEDS_BOARD_CONFIGURATION' | 'TEMPORARILY_UNAVAILABLE' | 'BLOCKED' | 'UNVERIFIED';
  configuration: string;
  configured_sources: number;
  enabled_sources: number;
  failing_sources: number;
  last_success_at: string | null;
}

/** POST /jobs/import-url result. */
export interface BoardImportResult {
  status: 'RESOLVED' | 'NEEDS_CONFIGURATION' | 'UNSUPPORTED' | 'UNRECOGNIZED' | 'CONFIGURED' | 'SOURCE_DISABLED' | 'RESOLUTION_PENDING';
  url?: string;
  message?: string;
  platform?: { kind: string; name: string; readiness: string; board_format: string };
  platforms?: Array<{ kind: string; name: string; readiness: string; board_format: string }>;
  board?: string;
  existing_source?: { id: string; enabled: boolean; display_name: string };
  preview?: { listed_on_first_page: number; more_pages_possible: boolean; sample_titles: string[]; employer?: string; problem?: string };
  source_id?: string;
  run?: { status: string; complete: boolean; counts: Record<string, number | boolean>; failure?: string };
}

export interface AuditLog {
  id: string;
  actor: string;
  action: string;
  entity_type?: string;
  entity_id?: string;
  before_state?: unknown;
  after_state?: unknown;
  ip?: string;
  correlation_id?: string;
  created_at: string;
}

export interface ResumeAtsAnalysis {
  id: string;
  profileId: string;
  jobId: string;
  applicationId?: string;
  inputHash: string;
  role: string;
  domain: string;
  requiredSkills: string[];
  preferredSkills: string[];
  normalizedSkills: Record<string, string>;
  verifiedEvidence: Array<{ source_type: string; evidence_id: string; claim: string; evidence_status: string }>;
  gaps: string[];
  atsReport: Record<string, unknown>;
  cvVersionId: string;
  resumeMarkdown: string;
  profileRevision: number;
  profileSnapshotHash: string;
  contentSha256: string;
}

// Matches the API's actual JSON casing (camelCase) — the previous snake_case
// declarations never matched the wire format, which left letter/answer bodies
// rendering as blank cards.
export interface CoverLetter {
  id: string;
  profileId: string;
  jobId: string;
  applicationId?: string;
  version: number;
  title: string;
  bodyMarkdown: string;
  claimsValidation: {
    passed: boolean;
    issues?: string[];
    findings?: ValidationFinding[];
    validator_version?: string;
    blocker_count?: number;
    warning_count?: number;
    scope?: string;
  };
  isApproved: boolean;
  createdAt: string;
  updatedAt: string;
  contentSha256?: string | null;
  parentVersionId?: string | null;
  origin?: 'GENERATED' | 'USER_CORRECTED';
  pdfStored?: boolean;
}

/** One finding from the backend DocumentFactValidator. */
export interface ValidationFinding {
  code: string;
  severity: 'BLOCKER' | 'WARNING';
  kind: 'DETERMINISTIC' | 'HEURISTIC';
  claim: string;
  message: string;
}

export interface DocumentValidation {
  validator_version: string;
  passed: boolean;
  blocker_count: number;
  warning_count: number;
  findings: ValidationFinding[];
  scope?: string;
}

/** GET /resume-intelligence/job/{jobId} */
export interface LatestCvResponse {
  cv: null | {
    analysis: ResumeAtsAnalysis;
    artifact: { present: boolean; intact: boolean; sha256: string | null; byteSize: number };
    review: null | { approved: boolean; decidedBy: string; decidedAt: string; contentSha256: string };
  };
}

export interface ComparisonItem { id: string; label: string }
export interface ComparisonEntry extends ComparisonItem {
  status: 'RETAINED' | 'SHORTENED' | 'OMITTED';
  sourceBullets: number;
  renderedBullets: number;
}

/** GET /resume-intelligence/cv/{id}/comparison */
export interface CvComparison {
  cvVersionId: string;
  generatedFromProfileRevision: number;
  currentProfileRevision: number;
  sourceMatchesGeneration: boolean;
  sourceNote: string;
  method: string;
  skills: { emphasisedForJob: ComparisonItem[]; retained: ComparisonItem[]; omitted: ComparisonItem[] };
  requirements: {
    evidenced: Array<{ requirement: string; sourceType: string; sourceId: string; sourceLabel: string }>;
    missingFromProfile: string[];
    preferred: string[];
    note: string;
  };
  sections: Array<{ section: string; items: ComparisonEntry[] }>;
  inCvButNotInCurrentProfile: string[];
  attention: Array<{ severity: string; kind: string; message: string }>;
  sourceRecords: {
    summaryPresent: boolean;
    skills: ComparisonItem[];
    experiences: ComparisonItem[];
    projects: ComparisonItem[];
    education: ComparisonItem[];
    certifications: ComparisonItem[];
  };
}

export interface PrepItem { area: string; code: string; message: string }

/** GET /prep/jobs/{jobId}/readiness */
export interface PrepReadiness {
  jobId: string;
  applicationId: string | null;
  applicationStatus: string | null;
  overall: 'NOT_STARTED' | 'BLOCKED' | 'IN_PROGRESS' | 'READY_FOR_REVIEW';
  overallLabel: string;
  cv: { state: string; cvVersionId?: string; linkedToApplication?: boolean | null; artifactIntact?: boolean; approved?: boolean };
  coverLetter: { state: string; requirement: string; versions: number; coverLetterId?: string; version?: number; approved?: boolean };
  answers: { total: number; confirmed: number; unconfirmed: number; needsInput: number; requiredQuestionsKnown: boolean };
  blockers: PrepItem[];
  actions: PrepItem[];
  notChecked: string[];
  note: string;
  computedAt: string;
}
export interface ApplicationAnswer {
  id: string;
  profileId: string;
  jobId: string;
  applicationId?: string;
  questionText: string;
  questionType: string;
  answerText: string;
  confidence: number;
  status: 'ANSWERED' | 'NEEDS_USER_INPUT' | 'HARD_STOP';
  humanConfirmed: boolean;
  validationNotes?: {
    questionType?: string;
    issues?: string[];
  };
  createdAt: string;
  updatedAt: string;
}

/** One actionable blocker/warning from the server-side APPLY readiness gate. */
export interface ApplyReadinessItem {
  area: string;
  code: string;
  message: string;
}

/**
 * One employer form question as captured from the real application form.
 * requiredState is evidence-backed tri-state: UNKNOWN never means optional.
 * answerState distinguishes confirmed candidate answers from drafts.
 */
export interface ApplyQuestionItem {
  questionKey: string;
  questionText: string;
  requiredState: 'REQUIRED' | 'OPTIONAL' | 'UNKNOWN';
  answerType: string;
  options: string[];
  answerState: 'CONFIRMED' | 'UNCONFIRMED' | 'MISSING' | 'NEEDS_USER_INPUT' | 'HARD_STOP' | string;
  answerOrigin?: string | null;
  answerId?: string | null;
  formUrl: string;
}

/** GET /apply/applications/{id}/readiness — recomputed from records per request. */
export interface ApplyReadiness {
  availability: 'AVAILABLE' | 'UNAVAILABLE';
  application: {
    applicationId: string;
    jobId: string;
    jobTitle: string;
    company: string;
    applicationUrl: string;
    status: string;
  };
  documents: {
    cv: { versionId: string; pdfSha256: string; byteSize: number; reviewedAt: string } | null;
    coverLetter: {
      versionId: string;
      version: number;
      origin: string;
      bodySha256: string;
      pdfSha256: string;
    } | null;
    coverLetterRequirement: 'REQUIRED' | 'OPTIONAL' | 'UNKNOWN';
    selectionBlocked: boolean;
  };
  questions: {
    formCaptured: boolean;
    formUrl: string | null;
    items: ApplyQuestionItem[];
    storedAnswerCount: number;
    confirmedCount: number;
  };
  duplicates: {
    checked: boolean;
    status: 'NONE' | 'DUPLICATE';
    items: Array<{ applicationId: string; jobId: string; matchReason: string }>;
  };
  decision: { applicationMode: string; ruleAvailability: string };
  blockers: ApplyReadinessItem[];
  warnings: ApplyReadinessItem[];
  unknowns: string[];
  packageReady: boolean;
  packagePreview: {
    documents: { cv: string | null; coverLetter: string | null };
    coverLetterRequirement: string;
    note: string;
  };
  note: string;
  computedAt: string;
}


/* ------------------------------------------------------------------ */
/* TRACK (Phase 8.3)                                                   */
/* ------------------------------------------------------------------ */

/** Reconciled, candidate-facing application stage (derived at read time, never stored). */
export type TrackStage =
  | 'PREPARING'
  | 'READY_TO_APPLY'
  | 'APPLYING'
  | 'APPLIED'
  | 'ACKNOWLEDGED'
  | 'IN_CONTACT'
  | 'ASSESSMENT'
  | 'INTERVIEW'
  | 'OFFER'
  | 'REJECTED'
  | 'WITHDRAWN'
  | 'STOPPED'
  | 'NEEDS_REVIEW'
  | 'UNKNOWN';

/** What the current stage rests on, strongest first. */
export type TrackBasis = 'VERIFIED' | 'CANDIDATE_CONFIRMED' | 'CANDIDATE_REPORTED' | 'UNVERIFIED' | 'INTERNAL' | 'INFERRED';
export type TrackSubmissionState = 'CONFIRMED' | 'UNVERIFIED' | 'UNKNOWN' | 'CONFLICTING';
export type TrackAcknowledgementState = 'VERIFIED' | 'UNVERIFIED' | 'NONE';
export type TrackEventClassification = 'INTERNAL' | 'EXTERNAL' | 'INFERRED' | 'CANDIDATE_REPORTED';
export type TrackVerification = 'NOT_APPLICABLE' | 'VERIFIED' | 'UNVERIFIED' | 'REJECTED' | 'UNKNOWN';
export type TrackTimePrecision = 'EXACT' | 'DATE' | 'UNKNOWN';
export type TrackReportKind =
  | 'SUBMITTED'
  | 'ACKNOWLEDGED'
  | 'RECRUITER_CONTACT'
  | 'ASSESSMENT'
  | 'INTERVIEW'
  | 'OFFER'
  | 'REJECTED'
  | 'WITHDRAWN';

/** Compact TRACK state carried by each row of GET /applications. */
export interface TrackSummary {
  availability: 'AVAILABLE' | 'UNAVAILABLE';
  status?: TrackStage;
  label?: string;
  basis?: TrackBasis | null;
  submission?: TrackSubmissionState;
  needsReview?: boolean;
  attentionCount?: number;
  pending?: string | null;
}

export interface TrackApplicationRef {
  applicationId: string;
  jobTitle: string;
  company: string;
  location?: string | null;
  createdAt?: string | null;
}

export interface TrackAttention {
  code: string;
  message: string;
  emailId?: string | null;
  receiptId?: string | null;
}

export interface TrackConflict {
  code: string;
  message: string;
}

/** One piece of submission evidence and the verification policy's outcome for it. */
export interface TrackReceipt {
  id: string;
  evidenceType: 'EMPLOYER_ACKNOWLEDGEMENT_EMAIL' | 'CANDIDATE_STATEMENT';
  evidenceLabel: string;
  channel: 'MAILBOX_CONNECTOR' | 'CANDIDATE_FORWARDED' | 'CANDIDATE_ENTERED';
  channelLabel: string;
  verification: TrackVerification;
  verificationLabel: string;
  /** Why the verification policy reached its outcome, one entry per rule checked. */
  reasons: Array<{ code: string; message: string }>;
  policyVersion: string;
  summary: string | null;
  externalReference: string | null;
  evidenceAt: string | null;
  evidenceAtPrecision: TrackTimePrecision;
  capturedAt: string | null;
  candidateReview: 'CONFIRMED' | 'REJECTED' | null;
  candidateReviewedAt: string | null;
  reviewVersion: number;
  emailId: string | null;
}

/** An employer message waiting for the candidate's decision. */
export interface TrackReviewItem {
  emailId: string;
  status: string;
  reviewType: 'CONFLICT' | 'ASSOCIATION' | 'UNMATCHED' | 'MEANING';
  subject: string | null;
  from: string | null;
  receivedAt: string | null;
  receivedTimeKnown: boolean;
  category: string;
  categoryLabel: string;
  categoryConfirmed: boolean;
  confidence: number | null;
  confidenceNote: string;
  excerpt: string | null;
  rationale: Array<{ code: string; message: string }> | null;
  decisionVersion: number;
  addedBy: 'YOU' | 'MAILBOX_CONNECTOR';
  currentApplication: TrackApplicationRef | null;
  candidates: Array<TrackApplicationRef & { signals: string[] }>;
  allowedCategories: Array<{ value: string; label: string }>;
}

/** GET /applications/{id}/track */
export interface ApplicationTrack {
  applicationId: string;
  availability: 'AVAILABLE';
  application: TrackApplicationRef;
  status: TrackStage;
  label: string;
  basis: TrackBasis | null;
  basisLabel: string | null;
  reason: string;
  since: string | null;
  sincePrecision: TrackTimePrecision | null;
  evidenceStage: TrackStage;
  recordedStatus: string;
  recordedStatusLabel: string;
  submission: { state: TrackSubmissionState; label: string; reason: string };
  acknowledgement: { state: TrackAcknowledgementState; label: string };
  communications: { linkedMessages: number; awaitingReview: number };
  pending: { stage: TrackStage; label: string; emailId: string | null } | null;
  attention: TrackAttention[];
  unknowns: string[];
  conflicts: TrackConflict[];
  needsReview: boolean;
  robinSubmission: { submitted: boolean; note: string };
  receipts: TrackReceipt[];
  reviews: TrackReviewItem[];
  mailbox: { connected: boolean; note: string };
  versions?: Record<string, string>;
  computedAt: string;
  replayed?: boolean;
}

/** One neutral timeline entry (no discovery-source or platform names by construction). */
export interface TrackTimelineItem {
  id: string;
  type: string;
  summary: string;
  classification: TrackEventClassification;
  verification: TrackVerification;
  label: string;
  actor: 'ROBIN' | 'YOU' | 'EMPLOYER';
  occurredAt: string | null;
  occurredOn: string | null;
  occurredAtPrecision: TrackTimePrecision;
  recordedAt: string | null;
  supersedes: string | null;
  receiptId: string | null;
  emailId: string | null;
}

/** GET /applications/{id}/track/timeline — `nextCursor` is "" when there is no further page. */
export interface TrackTimelinePage {
  items: TrackTimelineItem[];
  nextCursor: string;
  limit: number;
}

/** GET /track/reviews */
export interface TrackReviewQueueResponse {
  items: TrackReviewItem[];
  mailboxConnected: boolean;
}

/** POST /emails/ingest */
export interface EmailIngestResult {
  emailId: string | null;
  classification: string | null;
  applicationId: string | null;
  replayed: boolean;
  associationStatus: string | null;
  needsReview: boolean;
  receiptVerification: string | null;
}

/** Application Submission Receipt (Phase 20) */
export interface IdentityReceipt {
  fullName: string | null;
  email: string | null;
  phone: string | null;
  city: string | null;
  country: string | null;
  rightToWorkUk: boolean | null;
  requiresVisaSponsorship: boolean | null;
}

export interface DocumentReceipt {
  cvId: string | null;
  cvVersion: number | null;
  cvFilename: string | null;
  cvSha256: string | null;
  coverLetterId: string | null;
  coverLetterTitle: string | null;
  coverLetterSha256: string | null;
  coverLetterProvided: boolean;
}

export interface AnswerReceipt {
  key: string;
  questionText: string;
  answerText: string;
}

export interface ConfirmationEvidence {
  confirmed: boolean;
  confirmationNumber: string | null;
  receiptToken: string | null;
  detectedAt: string | null;
  pageTitle: string | null;
  observedSignals: string[];
}

export interface ReconciliationDetails {
  needed: boolean;
  reason: string | null;
  lastReconciledAt: string | null;
  attemptCount: number;
}

export interface FailureDetails {
  failed: boolean;
  failureStage: string | null;
  errorMessage: string | null;
  occurredAt: string | null;
}

export interface AuthorizationRecord {
  authorized: boolean;
  authorizedBy: string | null;
  authorizedAt: string | null;
  consentSummary: string | null;
}

export interface AttemptSummary {
  attemptId: string;
  idempotencyKey: string;
  status: string;
  stage: string | null;
  createdAt: string | null;
  completedAt: string | null;
  isCurrent: boolean;
}

export interface SubmissionReceipt {
  receiptId: string;
  applicationId: string;
  attemptId: string;
  idempotencyKey: string;
  companyName: string | null;
  jobTitle: string | null;
  applicationUrl: string | null;
  status: string;
  failureStage: string | null;
  failureReason: string | null;
  generatedAt: string;
  submittedAt: string | null;
  identity: IdentityReceipt;
  documents: DocumentReceipt;
  answers: AnswerReceipt[];
  confirmation: ConfirmationEvidence;
  reconciliation: ReconciliationDetails;
  failure: FailureDetails;
  authorization: AuthorizationRecord;
  attempts: AttemptSummary[];
}
