// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiDownload, apiFetch, saveDownload } from '../api/client';
import { PrepWorkspace } from './PrepWorkspace';

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(),
  apiDownload: vi.fn(),
  saveDownload: vi.fn(),
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedFetch = vi.mocked(apiFetch);
const mockedDownload = vi.mocked(apiDownload);

const readiness = (overrides: Record<string, unknown> = {}) => ({
  jobId: 'job-1',
  applicationId: 'app-1',
  applicationStatus: 'READY_TO_APPLY',
  overall: 'BLOCKED',
  overallLabel: 'Blocked',
  cv: { state: 'VALIDATION_BLOCKED' },
  coverLetter: { state: 'AWAITING_APPROVAL', requirement: 'UNKNOWN', versions: 2 },
  answers: { total: 1, confirmed: 0, unconfirmed: 1, needsInput: 0, requiredQuestionsKnown: false },
  blockers: [{ area: 'CV', code: 'CV_VALIDATION_BLOCKED', message: 'The CV has blocking validation findings.' }],
  actions: [{ area: 'ANSWERS', code: 'ANSWER_UNCONFIRMED', message: 'Review and confirm the answer to "Why us?".' }],
  notChecked: ['ATS-specific required questions: not available for this job.'],
  note: 'Derived from stored records.',
  computedAt: '2026-10-10T10:00:00Z',
  ...overrides,
});

const validation = (passed: boolean) => ({
  validator_version: 'fact-validator-1',
  passed,
  blocker_count: passed ? 0 : 1,
  warning_count: 1,
  scope: 'Passing does not prove every sentence is true.',
  findings: [
    ...(passed ? [] : [{ code: 'UNSUPPORTED_FIGURE', severity: 'BLOCKER', kind: 'DETERMINISTIC', claim: '40%', message: 'The figure "40%" does not appear in any of your profile records.' }]),
    { code: 'REQUIREMENT_WITHOUT_EVIDENCE', severity: 'WARNING', kind: 'HEURISTIC', claim: 'Kubernetes', message: '"Kubernetes" is a requirement of the job, and your profile has no evidence for it.' },
  ],
});

const cvResponse = (passed: boolean, approved = false) => ({
  cv: {
    analysis: {
      id: 'a-1', profileId: 'p', jobId: 'job-1', applicationId: 'app-1', inputHash: 'h', role: 'Engineer', domain: 'X',
      requiredSkills: ['java', 'kubernetes'], preferredSkills: [], normalizedSkills: {},
      verifiedEvidence: [{ source_type: 'SKILL', evidence_id: 's1', claim: 'java', evidence_status: 'USER_VERIFIED' }],
      gaps: ['kubernetes'],
      atsReport: { keyword_coverage: 50, validation: validation(passed), renderer: { pages: 2, substituted_characters: 0 } },
      cvVersionId: 'cv-12345678', resumeMarkdown: '# Zoë Brontë\n\n## Skills\nJava', profileRevision: 4,
      profileSnapshotHash: 's', contentSha256: 'abc',
    },
    artifact: { present: true, intact: true, sha256: 'abc', byteSize: 1000 },
    review: approved ? { approved: true, decidedBy: 'me', decidedAt: '2026-10-10T10:00:00Z', contentSha256: 'abc' } : null,
  },
});

const comparison = {
  cvVersionId: 'cv-12345678', generatedFromProfileRevision: 4, currentProfileRevision: 4,
  sourceMatchesGeneration: true, sourceNote: 'Your profile is unchanged since this CV was generated.',
  method: 'Deterministic text and record comparison. No AI is used to describe differences.',
  skills: { emphasisedForJob: [{ id: 's1', label: 'Java' }], retained: [{ id: 's2', label: 'PostgreSQL' }], omitted: [] },
  requirements: { evidenced: [{ requirement: 'java', sourceType: 'SKILL', sourceId: 's1', sourceLabel: 'Skill: Java' }],
    missingFromProfile: ['kubernetes'], preferred: [], note: 'Missing requirements are not added to the CV.' },
  sections: [{ section: 'Experience', items: [{ id: 'e1', label: 'Engineer — Café Systems', status: 'SHORTENED', sourceBullets: 4, renderedBullets: 3 }] }],
  inCvButNotInCurrentProfile: [],
  attention: [{ severity: 'WARNING', kind: 'DETERMINISTIC', message: '1 job requirement(s) have no evidence in your profile: kubernetes' }],
  sourceRecords: { summaryPresent: false, skills: [], experiences: [], projects: [], education: [], certifications: [] },
};

const letters = [
  { id: 'cl-2', profileId: 'p', jobId: 'job-1', applicationId: 'app-1', version: 2, title: 'v2', bodyMarkdown: 'Corrected letter text',
    claimsValidation: validation(true), isApproved: false, createdAt: '', updatedAt: '', origin: 'USER_CORRECTED', parentVersionId: 'cl-1' },
  { id: 'cl-1', profileId: 'p', jobId: 'job-1', applicationId: 'app-1', version: 1, title: 'v1', bodyMarkdown: 'Original letter text',
    claimsValidation: validation(false), isApproved: false, createdAt: '', updatedAt: '', origin: 'GENERATED' },
];

function routes(overrides: Record<string, (init?: RequestInit) => unknown> = {}) {
  mockedFetch.mockImplementation(async (path: string, init?: RequestInit) => {
    const key = Object.keys(overrides).find((k) => path === k || path.startsWith(k + '?'));
    if (key) {
      const value = overrides[key](init);
      if (value instanceof Error) throw value;
      return value;
    }
    if (path === '/prep/jobs/job-1/readiness') return readiness();
    if (path.startsWith('/resume-intelligence/job/job-1')) return cvResponse(false);
    if (path === '/cover-letters/job/job-1') return letters;
    if (path === '/application-answers/job/job-1') return [];
    throw new Error('Unexpected API path: ' + path);
  });
}

function renderWorkspace() {
  return render(React.createElement(PrepWorkspace, { jobId: 'job-1' }));
}

describe('PrepWorkspace', () => {
  afterEach(() => cleanup());
  beforeEach(() => {
    vi.clearAllMocks();
    routes();
  });

  it('shows readiness blockers, remaining actions and what was not checked', async () => {
    renderWorkspace();
    expect(await screen.findByText('Blocked')).toBeTruthy();
    expect(screen.getByText(/The CV has blocking validation findings\./)).toBeTruthy();
    expect(screen.getByText(/Review and confirm the answer to "Why us\?"/)).toBeTruthy();
    expect(screen.getByText(/ATS-specific required questions: not available/)).toBeTruthy();
    expect(screen.getByText(/That status alone does not mean documents are prepared/)).toBeTruthy();
  });

  it('reports a readiness API failure as unavailable, not as an empty preparation', async () => {
    routes({ '/prep/jobs/job-1/readiness': () => new Error('PREP_UNAVAILABLE') });
    renderWorkspace();
    expect(await screen.findByText(/Preparation status is unavailable: PREP_UNAVAILABLE/)).toBeTruthy();
    expect(screen.queryByText('Not started')).toBeNull();
  });

  it('shows blocking CV findings and does not allow approval while blocked', async () => {
    renderWorkspace();
    expect(await screen.findByText('Blocked by validation')).toBeTruthy();
    expect(screen.getByText(/The figure "40%" does not appear/)).toBeTruthy();
    expect(screen.getAllByText(/heuristic, may be false positives/).length).toBeGreaterThan(0);
    expect((screen.getByRole('button', { name: 'Approve this CV' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/It is not an ATS score and guarantees nothing/)).toBeTruthy();
  });

  it('approves a valid CV through the review endpoint', async () => {
    let approved = false;
    routes({
      '/resume-intelligence/job/job-1': () => cvResponse(true, approved),
      '/resume-intelligence/cv/cv-12345678/review': () => { approved = true; return cvResponse(true, true).cv; },
    });
    renderWorkspace();
    await userEvent.click(await screen.findByRole('button', { name: 'Approve this CV' }));
    await waitFor(() => expect(mockedFetch).toHaveBeenCalledWith('/resume-intelligence/cv/cv-12345678/review',
      { method: 'PUT', body: JSON.stringify({ approved: true }) }));
    expect(await screen.findByText('Approved by you')).toBeTruthy();
  });

  it('generates a CV for the linked application when none exists', async () => {
    routes({
      '/resume-intelligence/job/job-1': () => ({ cv: null }),
      '/resume-intelligence/tailor': () => ({ cvVersionId: 'cv-new' }),
    });
    renderWorkspace();
    await userEvent.click(await screen.findByRole('button', { name: 'Generate job-specific CV' }));
    await waitFor(() => expect(mockedFetch).toHaveBeenCalledWith('/resume-intelligence/tailor',
      { method: 'POST', body: JSON.stringify({ jobId: 'job-1', applicationId: 'app-1' }) }));
  });

  it('renders the deterministic profile-versus-CV comparison', async () => {
    routes({ '/resume-intelligence/cv/cv-12345678/comparison': () => comparison });
    renderWorkspace();
    await userEvent.click(await screen.findByRole('button', { name: 'Compare with your profile' }));
    expect(await screen.findByText(/No AI is used to describe differences/)).toBeTruthy();
    expect(screen.getByText('• Java')).toBeTruthy();
    expect(screen.getByText('• java — Skill: Java')).toBeTruthy();
    expect(screen.getAllByText('• kubernetes').length).toBeGreaterThan(0);
    expect(screen.getByText('SHORTENED')).toBeTruthy();
    expect(screen.getByText(/\(3\/4 bullet points\)/)).toBeTruthy();
  });

  it('downloads the CV through the authenticated, checksum-verified client', async () => {
    mockedDownload.mockResolvedValue({ blob: new Blob(['x']), filename: 'tailored-cv.pdf', sha256: 'abcdef0123456789' });
    renderWorkspace();
    const buttons = await screen.findAllByRole('button', { name: 'Download PDF' });
    await userEvent.click(buttons[0]);
    expect(mockedDownload).toHaveBeenCalledWith('/resume-intelligence/cv/cv-12345678/artifact', 'tailored-cv-cv-12345678.pdf');
    expect(vi.mocked(saveDownload)).toHaveBeenCalled();
    expect(await screen.findByText(/Checksum verified/)).toBeTruthy();
  });

  it('shows a download integrity failure instead of saving the file', async () => {
    mockedDownload.mockRejectedValue(new Error('The downloaded file does not match its recorded checksum, so it was not saved.'));
    renderWorkspace();
    const buttons = await screen.findAllByRole('button', { name: 'Download PDF' });
    await userEvent.click(buttons[1]);
    expect(mockedDownload).toHaveBeenCalledWith('/cover-letters/cl-2/pdf', 'cover-letter-cl-2.pdf');
    expect(await screen.findByText(/does not match its recorded checksum/)).toBeTruthy();
    expect(vi.mocked(saveDownload)).not.toHaveBeenCalled();
  });

  it('lists letter versions, surfaces a refused approval and saves corrections as a new version', async () => {
    routes({
      '/cover-letters/cl-1/approval': () => new Error('This letter has 1 blocking validation finding(s). Submit a correction (a new version) before approving.'),
      '/cover-letters/cl-1/corrections': () => ({ coverLetter: { ...letters[0], id: 'cl-3', version: 3 } }),
    });
    renderWorkspace();
    expect(await screen.findByText('Corrected letter text')).toBeTruthy();
    expect(screen.getByRole('option', { name: /v2 \(your correction\)/ })).toBeTruthy();

    await userEvent.selectOptions(screen.getByLabelText('Version'), 'cl-1');
    expect(await screen.findByText('Original letter text')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Approve this version' }));
    expect(await screen.findByText(/Submit a correction \(a new version\) before approving/)).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: 'Correct this letter' }));
    const editor = screen.getByLabelText(/Corrected letter/);
    await userEvent.clear(editor);
    await userEvent.type(editor, 'Fixed text');
    await userEvent.click(screen.getByRole('button', { name: 'Save correction' }));
    await waitFor(() => expect(mockedFetch).toHaveBeenCalledWith('/cover-letters/cl-1/corrections',
      { method: 'POST', body: JSON.stringify({ bodyMarkdown: 'Fixed text' }) }));
    expect(await screen.findByText(/saved as a new version and re-validated/)).toBeTruthy();
  });

  it('distinguishes a valid empty state from a load failure for letters', async () => {
    routes({ '/cover-letters/job/job-1': () => new Error('storage down') });
    renderWorkspace();
    expect(await screen.findByText(/Cover letters could not be loaded: storage down/)).toBeTruthy();
    cleanup();
    routes({ '/cover-letters/job/job-1': () => [] });
    renderWorkspace();
    expect(await screen.findByText(/No cover letter has been generated for this job/)).toBeTruthy();
  });
});
