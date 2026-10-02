export interface User {
  id: string;
  email: string;
  displayName: string;
}

export interface Job {
  id: string;
  source_id: string;
  external_id: string;
  dedup_key: string;
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
  application_url?: string;
  canonical_url?: string;
  posted_at?: string;
  posted_date_source?: 'EXPLICIT' | 'INFERRED' | 'UNKNOWN';
  status: 'DISCOVERED' | 'FILTERED_OUT' | 'ANALYSED' | 'SCORED' | 'DECIDED' | 'ARCHIVED' | 'PIPELINE_ERROR';
  first_seen_at: string;
  last_seen_at: string;
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
    breakdown: {
      skill_overlap?: number;
      remote_fit?: number;
      salary_fit?: number;
      weights?: Record<string, number>;
      thresholds?: { apply: number; review: number };
      matched_skills?: string[];
      why?: string;
      decision?: string;
    } | null;
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
  applicationMode: 'MANUAL' | 'ASSISTED' | 'CONTROLLED_AUTO';
  scoringWeights: ScoringWeights;
  isActive: boolean;
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
  planSubmitApproved?: boolean | null;
  planHeartbeatAt?: string | null;
  planUpdatedAt?: string | null;
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
  rate_limit_per_min: number;
  enabled: boolean;
  failure_streak: number;
  last_run_at?: string;
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
  };
  isApproved: boolean;
  createdAt: string;
  updatedAt: string;
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

