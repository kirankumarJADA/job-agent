// @vitest-environment jsdom
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch, ApiError } from '../api/client';
import type { ApplyReadiness } from '../types';
import { ApplyWorkspace } from './ApplyWorkspace';

vi.mock('../api/client', () => {
  class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  }
  return { apiFetch: vi.fn(), ApiError };
});

const mocked = vi.mocked(apiFetch);

function readiness(overrides: Partial<ApplyReadiness> = {}): ApplyReadiness {
  return {
    availability: 'AVAILABLE',
    application: {
      applicationId: 'app-1',
      jobId: 'job-1',
      jobTitle: 'Platform Engineer',
      company: 'Fixture Co',
      applicationUrl: 'https://boards.greenhouse.io/fixtureco/jobs/42',
      status: 'READY_TO_APPLY',
    },
    documents: {
      cv: {
        versionId: 'cv-v-123',
        pdfSha256: 'abcdef0123456789abcdef',
        byteSize: 4321,
        reviewedAt: '2026-10-10T10:00:00Z',
      },
      coverLetter: {
        versionId: 'cl-v-9',
        version: 2,
        origin: 'GENERATED',
        bodySha256: '11112222333344445555',
        pdfSha256: '',
      },
      coverLetterRequirement: 'OPTIONAL',
      selectionBlocked: false,
    },
    questions: {
      formCaptured: true,
      source: 'GREENHOUSE_PUBLIC_FORM',
      formUrl: 'https://boards.greenhouse.io/fixtureco/jobs/42',
      items: [
        {
          questionKey: 'question_42',
          questionText: 'Why do you want to join?',
          requiredState: 'REQUIRED',
          answerType: 'textarea',
          options: [],
          answerState: 'CONFIRMED',
          answerOrigin: 'CANDIDATE_CONFIRMED',
          answerId: 'ans-1',
          source: 'GREENHOUSE_PUBLIC_FORM',
          formUrl: 'https://boards.greenhouse.io/fixtureco/jobs/42',
        },
      ],
      storedAnswerCount: 1,
      confirmedCount: 1,
    },
    duplicates: { checked: true, status: 'NONE', items: [] },
    decision: { applicationMode: 'ASSISTED', ruleAvailability: 'CONFIGURED' },
    blockers: [],
    warnings: [],
    unknowns: [],
    packageReady: true,
    packagePreview: {
      documents: { cv: 'cv-v-123', coverLetter: 'cl-v-9' },
      coverLetterRequirement: 'OPTIONAL',
      note: 'The exact immutable versions above are what an execution package would carry. Nothing here submits an application.',
    },
    note: 'READY_TO_APPLY is never evidence of a submission. REAL_SUBMIT remains hard-stopped.',
    computedAt: '2026-10-10T10:00:00Z',
    ...overrides,
  };
}

function renderWorkspace(props: Partial<React.ComponentProps<typeof ApplyWorkspace>> = {}) {
  return render(
    <ApplyWorkspace applicationId="app-1" {...props} />,
  );
}

describe('ApplyWorkspace', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows a loading state while the readiness gate is being consulted', () => {
    mocked.mockReturnValue(new Promise(() => {}) as never);
    renderWorkspace();
    expect(screen.getByText(/Deriving the APPLY state from your records/)).toBeTruthy();
  });

  it('reports an explicit unavailable state on 503 and never a false ready', async () => {
    mocked.mockRejectedValue(
      new ApiError(
        503,
        JSON.stringify({
          availability: 'UNAVAILABLE',
          reason: 'APPLICATION_LOOKUP_FAILED',
          message: 'Preparation state could not be verified right now. Nothing has been approved.',
        }),
      ),
    );
    renderWorkspace();
    expect(await screen.findByText(/Preparation state unavailable/)).toBeTruthy();
    expect(screen.getByText(/Nothing has been approved/)).toBeTruthy();
    expect(screen.getByText(/Preparation state could not be verified right now/)).toBeTruthy();
  });

  it('shows an error state when readiness cannot be loaded at all', async () => {
    mocked.mockRejectedValue(new Error('network down'));
    renderWorkspace();
    expect(await screen.findByText(/Could not load the APPLY state/)).toBeTruthy();
  });

  it('renders document versions, unknown requirements and the package preview accurately', async () => {
    mocked.mockResolvedValue(
      readiness({
        documents: {
          cv: { versionId: 'cv-v-123', pdfSha256: 'abcdef0123456789abcdef', byteSize: 4321, reviewedAt: '2026-10-10T10:00:00Z' },
          coverLetter: null,
          coverLetterRequirement: 'UNKNOWN',
          selectionBlocked: false,
        },
        questions: {
          formCaptured: true,
          source: 'GREENHOUSE_PUBLIC_FORM',
          formUrl: 'https://boards.greenhouse.io/fixtureco/jobs/42',
          items: [
            {
              questionKey: 'question_7',
              questionText: 'Notice period?',
              requiredState: 'UNKNOWN',
              answerType: 'text',
              options: [],
              answerState: 'CONFIRMED',
              answerOrigin: 'CANDIDATE_CONFIRMED',
              answerId: 'ans-7',
              source: 'GREENHOUSE_PUBLIC_FORM',
              formUrl: 'https://boards.greenhouse.io/fixtureco/jobs/42',
            },
          ],
          storedAnswerCount: 1,
          confirmedCount: 1,
        },
        unknowns: ['The form does not say whether "Notice period?" is required.'],
      }),
    );
    renderWorkspace();

    // The exact selected version is shown in the package preview.
    expect(await screen.findByText('cv-v-123')).toBeTruthy();
    // Unknown requirements are rendered as unknown — never as optional.
    expect(screen.getAllByText(/UNKNOWN/).length).toBeGreaterThan(0);
    expect(screen.getByText(/The form does not say whether/)).toBeTruthy();
    // The missing cover letter is honestly reported as missing.
    expect(screen.getByText('No approved cover letter is bound to this application.')).toBeTruthy();
  });

  it('shows captured questions with provenance and an empty state when none exist', async () => {
    mocked.mockResolvedValue(
      readiness({
        questions: {
          formCaptured: true,
          source: 'GREENHOUSE_PUBLIC_FORM',
          formUrl: 'form',
          items: [],
          storedAnswerCount: 0,
          confirmedCount: 0,
        },
      }),
    );
    renderWorkspace();
    expect(await screen.findByText(/contains no screening questions/)).toBeTruthy();
  });

  it('renders blockers, disables package creation while they exist, and keeps unknowns distinct', async () => {
    mocked.mockResolvedValue(
      readiness({
        packageReady: false,
        blockers: [{ area: 'CV', code: 'CV_REVIEW_REQUIRED', message: 'This CV version has not been reviewed and approved.' }],
        unknowns: ['Unknown requirement stays unknown.'],
      }),
    );
    renderWorkspace();
    expect(await screen.findByText(/This CV version has not been reviewed and approved/)).toBeTruthy();
    const button = screen.getByRole('button', { name: /Prepare execution package/ }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(screen.getByText(/Blocked · 1 blocker/)).toBeTruthy();
    expect(screen.getByText(/Unknown requirement stays unknown/)).toBeTruthy();
  });

  it('warns about a duplicate application and identifies the earlier record', async () => {
    mocked.mockResolvedValue(
      readiness({
        duplicates: {
          checked: true,
          status: 'DUPLICATE',
          items: [{ applicationId: 'app-old', jobId: 'job-old', matchReason: 'same Greenhouse requisition identifier' }],
        },
      }),
    );
    renderWorkspace();
    expect(await screen.findByText(/Duplicate detected/)).toBeTruthy();
    expect(screen.getByText(/same Greenhouse requisition identifier/)).toBeTruthy();
    expect(screen.getByText(/app-old/)).toBeTruthy();
  });

  it('reports a server refusal verbatim and never shows a false success', async () => {
    const onChanged = vi.fn();
    mocked.mockResolvedValueOnce(readiness());
    mocked.mockRejectedValueOnce(
      new ApiError(
        409,
        JSON.stringify({
          error: 'this application is not ready for an execution package',
          blockers: [{ area: 'CV', code: 'CV_REVIEW_REQUIRED', message: 'review the CV first' }],
        }),
      ),
    );
    renderWorkspace({ onChanged });
    fireEvent.click(await screen.findByRole('button', { name: /Prepare execution package/ }));

    expect(await screen.findByText(/The server refused this action/)).toBeTruthy();
    expect(screen.getByText(/review the CV first/)).toBeTruthy();
    expect(screen.queryByText(/Confirmed by the server/)).toBeNull();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('claims approval only after the server confirms it', async () => {
    const onChanged = vi.fn();
    mocked.mockResolvedValueOnce(readiness());
    mocked.mockResolvedValueOnce({ status: 'READY_TO_SUBMIT', submissionEnabled: false });
    renderWorkspace({ planId: 'plan-1', planStatus: 'AWAITING_APPROVAL', onChanged });

    fireEvent.click(await screen.findByRole('button', { name: /Approve for submission/ }));

    expect(await screen.findByText(/Server confirmed the approval: READY_TO_SUBMIT/)).toBeTruthy();
    expect(screen.getByText(/REAL_SUBMIT is hard-stopped/)).toBeTruthy();
    expect(mocked).toHaveBeenCalledWith('/automation/plans/plan-1/approve-submit', { method: 'POST' });
    expect(onChanged).toHaveBeenCalled();
  });

  it('shows the server rejection when a stale approval attempt is refused', async () => {
    mocked.mockResolvedValueOnce(readiness());
    mocked.mockRejectedValueOnce(
      new ApiError(
        409,
        JSON.stringify({
          error: 'an answer in this package has changed since it was built; rebuild the package',
          blockers: [],
        }),
      ),
    );
    renderWorkspace({ planId: 'plan-1', planStatus: 'AWAITING_APPROVAL' });

    fireEvent.click(await screen.findByRole('button', { name: /Approve for submission/ }));

    expect(await screen.findByText(/The server refused this action/)).toBeTruthy();
    expect(screen.getByText(/has changed since it was built/)).toBeTruthy();
    expect(screen.queryByText(/Server confirmed the approval/)).toBeNull();
  });

  it('confirms an unconfirmed answer and refreshes the readiness state afterwards', async () => {
    const onChanged = vi.fn();
    mocked.mockResolvedValue(
      readiness({
        questions: {
          formCaptured: true,
          source: 'GREENHOUSE_PUBLIC_FORM',
          formUrl: 'form',
          items: [
            {
              questionKey: 'question_42',
              questionText: 'Why do you want to join?',
              requiredState: 'REQUIRED',
              answerType: 'textarea',
              options: [],
              answerState: 'UNCONFIRMED',
              answerOrigin: 'MODEL_DRAFT',
              answerId: 'ans-1',
              source: 'GREENHOUSE_PUBLIC_FORM',
              formUrl: 'form',
            },
          ],
          storedAnswerCount: 1,
          confirmedCount: 0,
        },
      }),
    );
    renderWorkspace({ onChanged });

    fireEvent.click(await screen.findByRole('button', { name: /Confirm this answer/ }));

    expect(mocked).toHaveBeenCalledWith('/application-answers/ans-1', {
      method: 'PUT',
      body: JSON.stringify({ confirmForAutofill: true }),
    });
    expect(await screen.findByText(/Answer confirmed/)).toBeTruthy();
    expect(onChanged).toHaveBeenCalled();
    // The state was re-derived from the server after the confirmation.
    const readinessCalls = mocked.mock.calls.filter(([url]) => String(url).includes('/readiness'));
    expect(readinessCalls.length).toBeGreaterThanOrEqual(2);
  });
});
