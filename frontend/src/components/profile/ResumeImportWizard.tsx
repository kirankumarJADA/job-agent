import React, { useRef, useState } from 'react';
import { apiFetch, ApiError } from '../../api/client';
import { Alert, Loading, PrimaryButton, SecondaryButton, Select, StatusPill, TextInput, Textarea } from '../ui';

type MatchStatus = 'NEW' | 'EXISTING' | 'POSSIBLE_DUPLICATE' | 'CONFLICT' | 'NEEDS_REVIEW';
type ImportAction = 'ADD' | 'REPLACE' | 'SKIP';

interface PersonalFieldCandidate {
  field: string;
  extractedValue: string | null;
  currentValue: string | null;
  sourceVerified: boolean;
}
interface ExtractedExperience {
  company: string | null; title: string | null; location: string | null;
  startMonth: string | null; endMonth: string | null;
  responsibilities: string[]; achievements: string[]; technologies: string[];
  sourceVerified: boolean; requiresReview: boolean;
}
interface ExtractedEducation {
  institution: string | null; qualification: string | null; field: string | null;
  startYear: number | null; endYear: number | null; grade: string | null;
  sourceVerified: boolean; requiresReview: boolean;
}
interface ExtractedProject {
  name: string | null; description: string | null; url: string | null;
  technologies: string[]; achievements: string[]; sourceVerified: boolean; requiresReview: boolean;
}
interface ExtractedSkill { name: string | null; category: string | null; sourceVerified: boolean }
interface ExtractedCertification {
  name: string | null; issuer: string | null; issuedOn: string | null; expiresOn: string | null;
  credentialId: string | null; verificationUrl: string | null; sourceVerified: boolean; requiresReview: boolean;
}

interface Candidate<T> { clientId: string; data: T; matchStatus: MatchStatus; matchedExistingId: string | null }

interface PreviewResponse {
  previewToken: string;
  expiresAt: string;
  offlineFallback: boolean;
  personal: PersonalFieldCandidate[];
  extractedProfessionalSummary: string | null;
  extractedCareerFocus: string | null;
  summarySourceVerified: boolean;
  experiences: Candidate<ExtractedExperience>[];
  education: Candidate<ExtractedEducation>[];
  projects: Candidate<ExtractedProject>[];
  skills: Candidate<ExtractedSkill>[];
  certifications: Candidate<ExtractedCertification>[];
  counts: { newCount: number; existingCount: number; possibleDuplicateCount: number; conflictCount: number; needsReviewCount: number };
}

interface Row<T> { candidate: Candidate<T>; include: boolean; action: ImportAction; edited: T }

type Step = 'upload' | 'processing' | 'review' | 'confirming' | 'done';

const STATUS_TONE: Record<MatchStatus, 'emerald' | 'amber' | 'red' | 'slate'> = {
  NEW: 'emerald', EXISTING: 'slate', POSSIBLE_DUPLICATE: 'amber', CONFLICT: 'red', NEEDS_REVIEW: 'amber',
};
const STATUS_LABEL: Record<MatchStatus, string> = {
  NEW: 'New', EXISTING: 'Already in your profile', POSSIBLE_DUPLICATE: 'Possible duplicate',
  CONFLICT: 'Conflicts with existing record', NEEDS_REVIEW: 'Needs review',
};

/**
 * Only NEW items start selected. Including anything else defaults to adding it
 * as a separate record; replacing an existing record is always a deliberate
 * second choice. EXISTING items are already in the profile and cannot be picked.
 */
function toRows<T>(candidates: Candidate<T>[]): Row<T>[] {
  return candidates.map((c) => ({
    candidate: c,
    include: c.matchStatus === 'NEW',
    action: c.matchStatus === 'NEW' ? 'ADD' : 'SKIP',
    edited: { ...(c.data as any) } as T,
  }));
}

function withInclude<T>(row: Row<T>, include: boolean): Row<T> {
  if (row.candidate.matchStatus === 'EXISTING') return row;
  return { ...row, include, action: include ? (row.action === 'SKIP' ? 'ADD' : row.action) : 'SKIP' };
}

/** The API stores months as the first day ("2021-01-01"); the review shows and edits just the month. */
function toMonthInput(date: string | null): string {
  return date ? date.slice(0, 7) : '';
}

function fromMonthInput(value: string): string | null {
  return /^\d{4}-\d{2}$/.test(value) ? `${value}-01` : null;
}

export const ResumeImportWizard: React.FC<{ onClose: () => void; onImported: () => void }> = ({ onClose, onImported }) => {
  const [step, setStep] = useState<Step>('upload');
  const [error, setError] = useState<string | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [preview, setPreview] = useState<PreviewResponse | null>(null);
  const [personalAccepted, setPersonalAccepted] = useState<Record<string, boolean>>({});
  const [personalValues, setPersonalValues] = useState<Record<string, string>>({});
  const [summaryAccepted, setSummaryAccepted] = useState(false);
  const [summaryValue, setSummaryValue] = useState('');

  const [experienceRows, setExperienceRows] = useState<Row<ExtractedExperience>[]>([]);
  const [educationRows, setEducationRows] = useState<Row<ExtractedEducation>[]>([]);
  const [projectRows, setProjectRows] = useState<Row<ExtractedProject>[]>([]);
  const [skillRows, setSkillRows] = useState<Row<ExtractedSkill>[]>([]);
  const [certificationRows, setCertificationRows] = useState<Row<ExtractedCertification>[]>([]);
  const [summaryCounts, setSummaryCounts] = useState({ added: 0, replaced: 0, skipped: 0, warnings: [] as string[] });

  const handleFile = async (file: File) => {
    setError(null);
    if (!/\.(pdf|docx)$/i.test(file.name)) {
      setError('Only PDF and DOCX resumes are supported.');
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setError('That file is larger than the 10 MB limit.');
      return;
    }
    setStep('processing');
    try {
      const formData = new FormData();
      formData.append('file', file);
      const response = await apiFetch<PreviewResponse>('/profile/resume-import/preview', { method: 'POST', body: formData });
      setPreview(response);
      const accepted: Record<string, boolean> = {};
      const values: Record<string, string> = {};
      response.personal.forEach((field) => {
        const shouldAccept = !field.currentValue && !!field.extractedValue;
        accepted[field.field] = shouldAccept;
        values[field.field] = field.extractedValue ?? '';
      });
      setPersonalAccepted(accepted);
      setPersonalValues(values);
      setSummaryValue(response.extractedProfessionalSummary ?? '');
      setSummaryAccepted(false);
      setExperienceRows(toRows(response.experiences));
      setEducationRows(toRows(response.education));
      setProjectRows(toRows(response.projects));
      setSkillRows(toRows(response.skills));
      setCertificationRows(toRows(response.certifications));
      setStep('review');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not process this resume. Please try again.');
      setStep('upload');
    }
  };

  const onDrop = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragActive(false);
    const file = event.dataTransfer.files?.[0];
    if (file) handleFile(file);
  };

  const confirmImport = async () => {
    if (!preview) return;
    setStep('confirming');
    setError(null);
    try {
      const personalFieldUpdates: Record<string, string> = {};
      Object.entries(personalAccepted).forEach(([field, accepted]) => {
        if (accepted && personalValues[field]) personalFieldUpdates[field] = personalValues[field];
      });

      const body = {
        previewToken: preview.previewToken,
        personalFieldUpdates,
        professionalSummaryUpdate: summaryAccepted ? summaryValue : null,
        experiences: experienceRows.filter((r) => r.include).map((r) => ({
          action: r.action, matchedExistingId: r.candidate.matchedExistingId,
          company: r.edited.company, title: r.edited.title, location: r.edited.location,
          startMonth: r.edited.startMonth, endMonth: r.edited.endMonth,
          responsibilities: r.edited.responsibilities, achievements: r.edited.achievements,
        })),
        education: educationRows.filter((r) => r.include).map((r) => ({
          action: r.action, matchedExistingId: r.candidate.matchedExistingId,
          institution: r.edited.institution, qualification: r.edited.qualification, field: r.edited.field,
          startYear: r.edited.startYear, endYear: r.edited.endYear, grade: r.edited.grade,
        })),
        projects: projectRows.filter((r) => r.include).map((r) => ({
          action: r.action, matchedExistingId: r.candidate.matchedExistingId,
          name: r.edited.name, summary: r.edited.description, url: r.edited.url,
          bullets: r.edited.achievements,
        })),
        skills: skillRows.filter((r) => r.include).map((r) => ({
          action: r.action, matchedExistingId: r.candidate.matchedExistingId,
          name: r.edited.name, category: r.edited.category,
        })),
        certifications: certificationRows.filter((r) => r.include).map((r) => ({
          action: r.action, matchedExistingId: r.candidate.matchedExistingId,
          name: r.edited.name, issuer: r.edited.issuer, issuedOn: r.edited.issuedOn,
          credentialId: r.edited.credentialId,
        })),
      };

      const result = await apiFetch<{ added: number; replaced: number; skipped: number; warnings: string[] }>(
        '/profile/resume-import/confirm',
        { method: 'POST', body: JSON.stringify(body) },
      );
      setSummaryCounts(result);
      setStep('done');
      onImported();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not save the import. Please try again.');
      setStep('review');
    }
  };

  const selectedCount =
    experienceRows.filter((r) => r.include).length +
    educationRows.filter((r) => r.include).length +
    projectRows.filter((r) => r.include).length +
    skillRows.filter((r) => r.include).length +
    certificationRows.filter((r) => r.include).length +
    Object.values(personalAccepted).filter(Boolean).length +
    (summaryAccepted ? 1 : 0);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/60 p-4" role="dialog" aria-modal="true" aria-labelledby="resume-import-title">
      <div className="flex max-h-[90vh] w-full max-w-4xl flex-col overflow-hidden rounded-xl border border-line bg-surface shadow-pop">
        <div className="flex items-center justify-between border-b border-line px-6 py-4">
          <h2 id="resume-import-title" className="text-lg font-bold text-ink">Import from Resume</h2>
          <button type="button" onClick={onClose} aria-label="Close" className="text-ink-muted hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600">
            ✕
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-5 space-y-4">
          {error && <Alert tone="error">{error}</Alert>}

          {step === 'upload' && (
            <div
              onDragOver={(e) => { e.preventDefault(); setDragActive(true); }}
              onDragLeave={() => setDragActive(false)}
              onDrop={onDrop}
              className={`flex flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed p-10 text-center transition-colors ${dragActive ? 'border-forest-600 bg-forest-50' : 'border-line bg-cream-50'}`}
            >
              <p className="text-sm font-semibold text-ink">Drag and drop your resume here</p>
              <p className="text-xs text-ink-muted">PDF or DOCX, up to 10 MB. We never store the file after it is processed.</p>
              <input
                ref={fileInputRef}
                type="file"
                accept=".pdf,.docx"
                className="sr-only"
                aria-label="Choose resume file"
                onChange={(e) => { const file = e.target.files?.[0]; if (file) handleFile(file); }}
              />
              <SecondaryButton type="button" onClick={() => fileInputRef.current?.click()}>Browse file</SecondaryButton>
              <p className="text-[11px] text-ink-muted">
                Your resume text is sent to Robin's configured AI provider only to extract this preview. Nothing is saved to your
                profile until you review and confirm it below.
              </p>
            </div>
          )}

          {step === 'processing' && (
            <Loading>Reading your resume and extracting your professional experience…</Loading>
          )}

          {step === 'confirming' && <Loading>Saving the information you approved…</Loading>}

          {step === 'done' && (
            <div className="space-y-3">
              <Alert tone="success">
                Import complete: {summaryCounts.added} added, {summaryCounts.replaced} replaced, {summaryCounts.skipped} skipped.
              </Alert>
              {summaryCounts.warnings.length > 0 && (
                <Alert tone="info">{summaryCounts.warnings.join(' ')}</Alert>
              )}
              <div className="flex justify-end">
                <PrimaryButton type="button" onClick={onClose}>Done</PrimaryButton>
              </div>
            </div>
          )}

          {step === 'review' && preview && (
            <div className="space-y-6">
              {preview.offlineFallback && (
                <Alert tone="error">
                  Robin's AI provider was unavailable, so this preview was produced by a basic offline parser that
                  only recognises simple layouts. Your resume did not leave Robin's server. Check every entry carefully
                  before importing it.
                </Alert>
              )}
              <Alert tone="info">
                {preview.counts.newCount} new · {preview.counts.existingCount} already in your profile ·{' '}
                {preview.counts.possibleDuplicateCount} possible duplicates · {preview.counts.conflictCount} conflicts ·{' '}
                {preview.counts.needsReviewCount} need review. Only what you select below will be saved.
              </Alert>

              <ReviewSection title="Personal information">
                {preview.personal.filter((f) => f.extractedValue).map((field) => (
                  <label key={field.field} className="flex items-center gap-3 rounded-lg border border-line bg-cream-50/70 p-3 text-xs">
                    <input
                      type="checkbox"
                      checked={!!personalAccepted[field.field]}
                      onChange={(e) => setPersonalAccepted((prev) => ({ ...prev, [field.field]: e.target.checked }))}
                      aria-label={`Apply ${field.field}`}
                    />
                    <span className="w-28 font-semibold text-ink-soft">{field.field}</span>
                    <TextInput
                      className="flex-1"
                      value={personalValues[field.field] ?? ''}
                      onChange={(e) => setPersonalValues((prev) => ({ ...prev, [field.field]: e.target.value }))}
                      aria-label={`${field.field} value`}
                    />
                    {field.currentValue && <span className="text-ink-muted">current: {field.currentValue}</span>}
                  </label>
                ))}
              </ReviewSection>

              {preview.extractedProfessionalSummary && (
                <ReviewSection title="Professional summary">
                  <label className="flex items-start gap-3 text-xs">
                    <input
                      type="checkbox"
                      checked={summaryAccepted}
                      onChange={(e) => setSummaryAccepted(e.target.checked)}
                      aria-label="Apply professional summary"
                      className="mt-1"
                    />
                    <Textarea className="flex-1" rows={3} value={summaryValue} onChange={(e) => setSummaryValue(e.target.value)} />
                  </label>
                </ReviewSection>
              )}

              <ReviewSection title={`Work experience (${experienceRows.length})`}>
                {experienceRows.map((row, idx) => (
                  <CandidateRow key={row.candidate.clientId} row={row} statusTone={STATUS_TONE[row.candidate.matchStatus]}
                    statusLabel={STATUS_LABEL[row.candidate.matchStatus]} hasExisting={!!row.candidate.matchedExistingId}
                    onToggle={(include) => setExperienceRows((rows) => rows.map((r, i) => (i === idx ? withInclude(r, include) : r)))}
                    onAction={(action) => setExperienceRows((rows) => rows.map((r, i) => (i === idx ? { ...r, action } : r)))}
                  >
                    <div className="grid gap-2 md:grid-cols-2">
                      <TextInput aria-label="Company" value={row.edited.company ?? ''} onChange={(e) => setExperienceRows((rows) => rows.map((r, i) => (i === idx ? { ...r, edited: { ...r.edited, company: e.target.value } } : r)))} placeholder="Company" />
                      <TextInput aria-label="Title" value={row.edited.title ?? ''} onChange={(e) => setExperienceRows((rows) => rows.map((r, i) => (i === idx ? { ...r, edited: { ...r.edited, title: e.target.value } } : r)))} placeholder="Title" />
                      <TextInput type="month" aria-label="Start month" value={toMonthInput(row.edited.startMonth)} onChange={(e) => setExperienceRows((rows) => rows.map((r, i) => (i === idx ? { ...r, edited: { ...r.edited, startMonth: fromMonthInput(e.target.value) } } : r)))} />
                      <TextInput type="month" aria-label="End month (leave empty if current)" value={toMonthInput(row.edited.endMonth)} onChange={(e) => setExperienceRows((rows) => rows.map((r, i) => (i === idx ? { ...r, edited: { ...r.edited, endMonth: fromMonthInput(e.target.value) } } : r)))} />
                    </div>
                    {!row.candidate.data.sourceVerified && (
                      <p className="text-amber-700">Not found verbatim in the uploaded resume — double-check before saving.</p>
                    )}
                  </CandidateRow>
                ))}
                {experienceRows.length === 0 && <EmptySection text="No work experience detected." />}
              </ReviewSection>

              <ReviewSection title={`Education (${educationRows.length})`}>
                {educationRows.map((row, idx) => (
                  <CandidateRow key={row.candidate.clientId} row={row} statusTone={STATUS_TONE[row.candidate.matchStatus]}
                    statusLabel={STATUS_LABEL[row.candidate.matchStatus]} hasExisting={!!row.candidate.matchedExistingId}
                    onToggle={(include) => setEducationRows((rows) => rows.map((r, i) => (i === idx ? withInclude(r, include) : r)))}
                    onAction={(action) => setEducationRows((rows) => rows.map((r, i) => (i === idx ? { ...r, action } : r)))}
                  >
                    <div className="grid gap-2 md:grid-cols-2">
                      <TextInput aria-label="Institution" value={row.edited.institution ?? ''} onChange={(e) => setEducationRows((rows) => rows.map((r, i) => (i === idx ? { ...r, edited: { ...r.edited, institution: e.target.value } } : r)))} placeholder="Institution" />
                      <TextInput aria-label="Qualification" value={row.edited.qualification ?? ''} onChange={(e) => setEducationRows((rows) => rows.map((r, i) => (i === idx ? { ...r, edited: { ...r.edited, qualification: e.target.value } } : r)))} placeholder="Qualification" />
                    </div>
                  </CandidateRow>
                ))}
                {educationRows.length === 0 && <EmptySection text="No education detected." />}
              </ReviewSection>

              <ReviewSection title={`Projects (${projectRows.length})`}>
                {projectRows.map((row, idx) => (
                  <CandidateRow key={row.candidate.clientId} row={row} statusTone={STATUS_TONE[row.candidate.matchStatus]}
                    statusLabel={STATUS_LABEL[row.candidate.matchStatus]} hasExisting={!!row.candidate.matchedExistingId}
                    onToggle={(include) => setProjectRows((rows) => rows.map((r, i) => (i === idx ? withInclude(r, include) : r)))}
                    onAction={(action) => setProjectRows((rows) => rows.map((r, i) => (i === idx ? { ...r, action } : r)))}
                  >
                    <TextInput aria-label="Project name" value={row.edited.name ?? ''} onChange={(e) => setProjectRows((rows) => rows.map((r, i) => (i === idx ? { ...r, edited: { ...r.edited, name: e.target.value } } : r)))} placeholder="Name" />
                  </CandidateRow>
                ))}
                {projectRows.length === 0 && <EmptySection text="No projects detected." />}
              </ReviewSection>

              <ReviewSection title={`Skills (${skillRows.length})`}>
                <div className="flex flex-wrap gap-2">
                  {skillRows.map((row, idx) => (
                    <label key={row.candidate.clientId} className={`flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs ${row.candidate.matchStatus === 'EXISTING' ? 'border-line bg-cream-50 text-ink-muted' : 'border-forest-300 bg-forest-50 text-forest-800'}`}>
                      <input type="checkbox" checked={row.include} disabled={row.candidate.matchStatus === 'EXISTING'}
                        onChange={(e) => setSkillRows((rows) => rows.map((r, i) => (i === idx ? withInclude(r, e.target.checked) : r)))}
                        aria-label={`Include skill ${row.edited.name}`} />
                      {row.edited.name}
                      {row.candidate.matchStatus === 'EXISTING' && <span className="text-[10px]">(already have this)</span>}
                    </label>
                  ))}
                </div>
                {skillRows.length === 0 && <EmptySection text="No skills detected." />}
              </ReviewSection>

              <ReviewSection title={`Certifications (${certificationRows.length})`}>
                {certificationRows.map((row, idx) => (
                  <CandidateRow key={row.candidate.clientId} row={row} statusTone={STATUS_TONE[row.candidate.matchStatus]}
                    statusLabel={STATUS_LABEL[row.candidate.matchStatus]} hasExisting={!!row.candidate.matchedExistingId}
                    onToggle={(include) => setCertificationRows((rows) => rows.map((r, i) => (i === idx ? withInclude(r, include) : r)))}
                    onAction={(action) => setCertificationRows((rows) => rows.map((r, i) => (i === idx ? { ...r, action } : r)))}
                  >
                    <TextInput aria-label="Certification name" value={row.edited.name ?? ''} onChange={(e) => setCertificationRows((rows) => rows.map((r, i) => (i === idx ? { ...r, edited: { ...r.edited, name: e.target.value } } : r)))} placeholder="Name" />
                  </CandidateRow>
                ))}
                {certificationRows.length === 0 && <EmptySection text="No certifications detected." />}
              </ReviewSection>
            </div>
          )}
        </div>

        {step === 'review' && (
          <div className="flex items-center justify-between border-t border-line px-6 py-4">
            <span className="text-xs text-ink-muted">{selectedCount} item(s) will be saved</span>
            <div className="flex gap-2">
              <SecondaryButton type="button" onClick={onClose}>Cancel</SecondaryButton>
              <PrimaryButton type="button" onClick={confirmImport} disabled={selectedCount === 0}>
                Import selected
              </PrimaryButton>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

const ReviewSection: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <div className="space-y-2">
    <h3 className="text-sm font-semibold text-ink">{title}</h3>
    <div className="space-y-2">{children}</div>
  </div>
);

const EmptySection: React.FC<{ text: string }> = ({ text }) => (
  <p className="rounded-lg border border-dashed border-line bg-cream-50 px-4 py-3 text-xs text-ink-muted">{text}</p>
);

function CandidateRow<T>({
  row, children, statusTone, statusLabel, hasExisting, onToggle, onAction,
}: {
  row: Row<T>;
  children: React.ReactNode;
  statusTone: 'emerald' | 'amber' | 'red' | 'slate';
  statusLabel: string;
  hasExisting: boolean;
  onToggle: (include: boolean) => void;
  onAction: (action: ImportAction) => void;
}) {
  const alreadyPresent = row.candidate.matchStatus === 'EXISTING';
  return (
    <div className="space-y-2 rounded-lg border border-line bg-cream-50/70 p-3">
      <div className="flex items-center justify-between gap-2">
        <label className={`flex items-center gap-2 text-xs font-semibold ${alreadyPresent ? 'text-ink-muted' : 'text-ink'}`}>
          <input type="checkbox" checked={row.include} disabled={alreadyPresent}
            onChange={(e) => onToggle(e.target.checked)} aria-label="Include this entry" />
          {alreadyPresent ? 'Already saved' : 'Include'}
        </label>
        <div className="flex items-center gap-2">
          <StatusPill tone={statusTone}>{statusLabel}</StatusPill>
          {row.include && hasExisting && (
            <Select aria-label="Action" value={row.action} onChange={(e) => onAction(e.target.value as ImportAction)}>
              <option value="ADD">Add as a separate entry</option>
              <option value="REPLACE">Replace the existing entry</option>
            </Select>
          )}
        </div>
      </div>
      {children}
    </div>
  );
}
