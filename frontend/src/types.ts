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
  score: {
    overall: number;
    recommendation: 'APPLY' | 'REVIEW' | 'SKIP';
    breakdown: Record<string, number>;
    explanation?: string;
  } | null;
  decision_trace: Array<{ step: string; outcome: string; reason?: string }>;
}

export interface WorkEligibility {
  right_to_work_uk: boolean;
  visa_status: string;
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

export interface PreferenceSet {
  id: string;
  profile_id: string;
  titles: string[];
  keywords_include: string[];
  keywords_exclude: string[];
  required_skills: string[];
  locations_allowed: string[];
  remote_types: string[];
  employment_types: string[];
  salary_min_gbp?: number;
  sponsorship_policy: 'SPONSORSHIP_REQUIRED' | 'SPONSORSHIP_PREFERRED' | 'SPONSORSHIP_NOT_REQUIRED' | 'SHOW_ALL';
  application_mode: 'MANUAL' | 'ASSISTED' | 'CONTROLLED_AUTO';
  scoring_weights: ScoringWeights;
  is_active: boolean;
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

export interface CoverLetter {
  id: string;
  profile_id: string;
  job_id: string;
  application_id?: string;
  version: number;
  title: string;
  body_markdown: string;
  claims_validation: {
    passed: boolean;
    issues?: string[];
  };
  is_approved: boolean;
  created_at: string;
  updated_at: string;
}export interface ApplicationAnswer {
  id: string;
  profile_id: string;
  job_id: string;
  application_id?: string;
  question_text: string;
  question_type: string;
  answer_text: string;
  confidence: number;
  status: 'ANSWERED' | 'NEEDS_USER_INPUT' | 'HARD_STOP';
  validation_notes?: {
    question_type?: string;
    issues?: string[];
  };
  created_at: string;
  updated_at: string;
}

