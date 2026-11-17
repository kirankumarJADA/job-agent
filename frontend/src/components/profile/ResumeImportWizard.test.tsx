// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiFetch, ApiError } from '../../api/client';
import { ResumeImportWizard } from './ResumeImportWizard';

afterEach(() => cleanup());
beforeEach(() => vi.clearAllMocks());

vi.mock('../../api/client', () => ({
  apiFetch: vi.fn(),
  ApiError: class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  },
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedApiFetch = vi.mocked(apiFetch);

const PREVIEW_RESPONSE = {
  previewToken: 'preview-1',
  expiresAt: '2030-01-01T00:00:00Z',
  offlineFallback: false,
  personal: [
    { field: 'headline', extractedValue: 'Senior Backend Engineer', currentValue: null, sourceVerified: true },
    { field: 'phone', extractedValue: null, currentValue: null, sourceVerified: true },
  ],
  extractedProfessionalSummary: 'Backend engineer focused on distributed systems.',
  extractedCareerFocus: null,
  summarySourceVerified: true,
  experiences: [
    {
      clientId: 'exp-0',
      data: {
        company: 'Acme Corp', title: 'Software Engineer', location: 'Austin, TX',
        startMonth: '2021-01-01', endMonth: '2023-12-01',
        responsibilities: ['Built payment services'], achievements: [], technologies: ['Java'],
        sourceVerified: true, requiresReview: false,
      },
      matchStatus: 'NEW',
      matchedExistingId: null,
    },
  ],
  education: [],
  projects: [],
  skills: [
    { clientId: 'skill-0', data: { name: 'Java', category: 'Technical', sourceVerified: true }, matchStatus: 'NEW', matchedExistingId: null },
  ],
  certifications: [],
  counts: { newCount: 2, existingCount: 0, possibleDuplicateCount: 0, conflictCount: 0, needsReviewCount: 0 },
};

function uploadFile() {
  const file = new File(['%PDF-1.7 fake pdf content'], 'resume.pdf', { type: 'application/pdf' });
  const input = screen.getByLabelText('Choose resume file') as HTMLInputElement;
  return userEvent.upload(input, file);
}

describe('ResumeImportWizard', () => {
  it('rejects a file that is not a PDF or DOCX before calling the API', async () => {
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));
    // Dropped (not browsed-to) files bypass the <input accept> filter a real
    // browser applies to the file picker, so this is the path that actually
    // reaches the component's own extension check.
    const file = new File(['hello'], 'resume.txt', { type: 'text/plain' });
    const dropzone = screen.getByText('Drag and drop your resume here').closest('div')!;
    fireEvent.drop(dropzone, { dataTransfer: { files: [file] } });
    expect(await screen.findByText(/Only PDF and DOCX resumes are supported/)).toBeTruthy();
    expect(mockedApiFetch).not.toHaveBeenCalled();
  });

  it('previews a resume and shows the review step with match counts', async () => {
    mockedApiFetch.mockResolvedValueOnce(PREVIEW_RESPONSE as any);
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));

    await uploadFile();

    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledWith(
      '/profile/resume-import/preview',
      expect.objectContaining({ method: 'POST' }),
    ));
    expect(await screen.findByText(/2 new/)).toBeTruthy();
    expect(screen.getByDisplayValue('Acme Corp')).toBeTruthy();
  });

  it('confirms the import with only the included items and reports the result', async () => {
    mockedApiFetch.mockResolvedValueOnce(PREVIEW_RESPONSE as any);
    mockedApiFetch.mockResolvedValueOnce({ added: 2, replaced: 0, skipped: 0, warnings: [] } as any);
    const onImported = vi.fn();
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported }));

    await uploadFile();
    await screen.findByText(/2 new/);

    await userEvent.click(screen.getByRole('button', { name: 'Import selected' }));

    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledWith(
      '/profile/resume-import/confirm',
      expect.objectContaining({ method: 'POST' }),
    ));
    const [, options] = mockedApiFetch.mock.calls[1];
    const body = JSON.parse((options as any).body);
    expect(body.previewToken).toBe('preview-1');
    expect(body.experiences).toHaveLength(1);
    expect(body.experiences[0].company).toBe('Acme Corp');
    expect(body.experiences[0].action).toBe('ADD');
    // Shown and edited as a month, stored as the first of that month.
    expect(body.experiences[0].startMonth).toBe('2021-01-01');
    expect(body.skills).toHaveLength(1);

    expect(await screen.findByText(/Import complete: 2 added/)).toBeTruthy();
    expect(onImported).toHaveBeenCalled();
  });

  it('shows months, not invented days, and sends an edited month as the first of that month', async () => {
    mockedApiFetch.mockResolvedValueOnce(PREVIEW_RESPONSE as any);
    mockedApiFetch.mockResolvedValueOnce({ added: 1, replaced: 0, skipped: 0, warnings: [] } as any);
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));
    await uploadFile();
    await screen.findByText(/2 new/);

    const start = screen.getByLabelText('Start month') as HTMLInputElement;
    expect(start.value).toBe('2021-01');
    fireEvent.change(start, { target: { value: '2020-06' } });
    await userEvent.click(screen.getByRole('button', { name: 'Import selected' }));

    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledTimes(2));
    const body = JSON.parse((mockedApiFetch.mock.calls[1][1] as any).body);
    expect(body.experiences[0].startMonth).toBe('2020-06-01');
  });

  it('including a possible duplicate adds it as a separate entry unless Replace is chosen explicitly', async () => {
    const possibleDuplicate = {
      ...PREVIEW_RESPONSE,
      experiences: [{ ...PREVIEW_RESPONSE.experiences[0], matchStatus: 'POSSIBLE_DUPLICATE', matchedExistingId: 'existing-exp-1' }],
      skills: [],
    };
    mockedApiFetch.mockResolvedValueOnce(possibleDuplicate as any);
    mockedApiFetch.mockResolvedValueOnce({ added: 0, replaced: 1, skipped: 0, warnings: [] } as any);
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));
    await uploadFile();
    await screen.findByText(/2 new/);

    // Not selected by default…
    const include = screen.getByLabelText('Include this entry') as HTMLInputElement;
    expect(include.checked).toBe(false);
    // …and ticking it must mean "add", never a silent skip.
    await userEvent.click(include);
    const action = screen.getByLabelText('Action') as HTMLSelectElement;
    expect(action.value).toBe('ADD');
    expect(Array.from(action.options).map((o) => o.value)).toEqual(['ADD', 'REPLACE']);

    await userEvent.selectOptions(action, 'REPLACE');
    await userEvent.click(screen.getByRole('button', { name: 'Import selected' }));

    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledTimes(2));
    const body = JSON.parse((mockedApiFetch.mock.calls[1][1] as any).body);
    expect(body.experiences).toEqual([
      expect.objectContaining({ action: 'REPLACE', matchedExistingId: 'existing-exp-1', company: 'Acme Corp' }),
    ]);
  });

  it('does not let an entry that is already in the profile be selected', async () => {
    mockedApiFetch.mockResolvedValueOnce({
      ...PREVIEW_RESPONSE,
      experiences: [{ ...PREVIEW_RESPONSE.experiences[0], matchStatus: 'EXISTING', matchedExistingId: 'existing-exp-1' }],
      skills: [{ ...PREVIEW_RESPONSE.skills[0], matchStatus: 'EXISTING', matchedExistingId: 'existing-skill-1' }],
    } as any);
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));
    await uploadFile();
    await screen.findByText(/2 new/);

    expect((screen.getByLabelText('Include this entry') as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByLabelText('Include skill Java') as HTMLInputElement).disabled).toBe(true);
  });

  it('warns clearly when the preview came from the offline fallback parser, not the AI provider', async () => {
    mockedApiFetch.mockResolvedValueOnce({ ...PREVIEW_RESPONSE, offlineFallback: true } as any);
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));
    await uploadFile();

    expect(await screen.findByText(/AI provider was unavailable/)).toBeTruthy();
  });

  it('shows an error and returns to the review step if confirm fails', async () => {
    mockedApiFetch.mockResolvedValueOnce(PREVIEW_RESPONSE as any);
    mockedApiFetch.mockRejectedValueOnce(new ApiError(409, 'This preview has expired.'));
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));

    await uploadFile();
    await screen.findByText(/2 new/);
    await userEvent.click(screen.getByRole('button', { name: 'Import selected' }));

    expect(await screen.findByText('This preview has expired.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Import selected' })).toBeTruthy();
  });

  it('defaults unverified items and unverified skills to unchecked', async () => {
    const unverifiedResponse = {
      ...PREVIEW_RESPONSE,
      personal: [
        { field: 'headline', extractedValue: 'Fabricated CTO', currentValue: null, sourceVerified: false },
      ],
      experiences: [
        {
          clientId: 'exp-unverified',
          data: {
            company: 'Acme Corp', title: 'Software Engineer', location: 'Austin, TX',
            startMonth: '2021', endMonth: '2023',
            responsibilities: ['Fake responsibility'], achievements: ['Fake metric'], technologies: ['Java'],
            sourceVerified: false, requiresReview: true,
          },
          matchStatus: 'NEW',
          matchedExistingId: null,
        },
      ],
      skills: [
        { clientId: 'skill-unverified', data: { name: 'Rust', category: 'Technical', sourceVerified: false }, matchStatus: 'NEW', matchedExistingId: null },
      ],
    };
    mockedApiFetch.mockResolvedValueOnce(unverifiedResponse as any);
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));
    await uploadFile();
    await screen.findByText(/2 new/);

    expect((screen.getByLabelText('Apply headline') as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText('Include this entry') as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText('Include skill Rust') as HTMLInputElement).checked).toBe(false);
  });

  it('defaults all items and personal fields to unchecked when offlineFallback is true', async () => {
    const offlineResponse = {
      ...PREVIEW_RESPONSE,
      offlineFallback: true,
      personal: [
        { field: 'headline', extractedValue: 'Senior Engineer', currentValue: null, sourceVerified: true },
      ],
      skills: [
        { clientId: 'skill-offline', data: { name: 'Java', category: 'Technical', sourceVerified: true }, matchStatus: 'NEW', matchedExistingId: null },
      ],
    };
    mockedApiFetch.mockResolvedValueOnce(offlineResponse as any);
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));
    await uploadFile();
    await screen.findByText(/2 new/);

    expect((screen.getByLabelText('Apply headline') as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText('Include this entry') as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText('Include skill Java') as HTMLInputElement).checked).toBe(false);
  });

  it('displays and allows editing of every persisted field across experience, education, projects, and certifications', async () => {
    const fullResponse = {
      ...PREVIEW_RESPONSE,
      experiences: [
        {
          clientId: 'exp-full',
          data: {
            company: 'Tech Corp', title: 'Lead Engineer', location: 'London, UK',
            startMonth: '2020-01-01', endMonth: '2022-06-01',
            responsibilities: ['Architected core platform'], achievements: ['Reduced latency 50%'], technologies: ['Go'],
            sourceVerified: true, requiresReview: false,
          },
          matchStatus: 'NEW',
          matchedExistingId: null,
        },
      ],
      education: [
        {
          clientId: 'edu-full',
          data: {
            institution: 'Oxford University', qualification: 'BSc', field: 'Computer Science',
            startYear: 2016, endYear: 2019, grade: 'First Class',
            sourceVerified: true, requiresReview: false,
          },
          matchStatus: 'NEW',
          matchedExistingId: null,
        },
      ],
      projects: [
        {
          clientId: 'proj-full',
          data: {
            name: 'OpenSource App', description: 'High throughput pipeline', url: 'https://github.com/test/app',
            technologies: ['React', 'TypeScript'], achievements: ['10k GitHub stars'],
            sourceVerified: true, requiresReview: false,
          },
          matchStatus: 'NEW',
          matchedExistingId: null,
        },
      ],
      certifications: [
        {
          clientId: 'cert-full',
          data: {
            name: 'AWS Solutions Architect', issuer: 'Amazon Web Services', issuedOn: '2021-05-01', expiresOn: null,
            credentialId: 'AWS-12345', verificationUrl: 'https://aws.cert/123',
            sourceVerified: true, requiresReview: false,
          },
          matchStatus: 'NEW',
          matchedExistingId: null,
        },
      ],
      skills: [],
      counts: { newCount: 4, existingCount: 0, possibleDuplicateCount: 0, conflictCount: 0, needsReviewCount: 0 },
    };
    mockedApiFetch.mockResolvedValueOnce(fullResponse as any);
    mockedApiFetch.mockResolvedValueOnce({ added: 4, replaced: 0, skipped: 0, warnings: [] } as any);
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));
    await uploadFile();
    await screen.findByText(/4 new/);

    // Verify all fields are rendered in the DOM
    expect(screen.getByLabelText('Location')).toBeTruthy();
    expect(screen.getByLabelText('Responsibilities')).toBeTruthy();
    expect(screen.getByLabelText('Achievements')).toBeTruthy();

    expect(screen.getByLabelText('Institution')).toBeTruthy();
    expect(screen.getByLabelText('Qualification')).toBeTruthy();
    expect(screen.getByLabelText('Field of study')).toBeTruthy();
    expect(screen.getByLabelText('Start year')).toBeTruthy();
    expect(screen.getByLabelText('End year')).toBeTruthy();
    expect(screen.getByLabelText('Grade')).toBeTruthy();

    expect(screen.getByLabelText('Project name')).toBeTruthy();
    expect(screen.getByLabelText('Project URL')).toBeTruthy();
    expect(screen.getByLabelText('Project description')).toBeTruthy();
    expect(screen.getByLabelText('Project achievements')).toBeTruthy();

    expect(screen.getByLabelText('Certification name')).toBeTruthy();
    expect(screen.getByLabelText('Certification issuer')).toBeTruthy();
    expect(screen.getByLabelText('Certification issue date')).toBeTruthy();
    expect(screen.getByLabelText('Certification credential ID')).toBeTruthy();

    // Edit fields across sections
    fireEvent.change(screen.getByLabelText('Location'), { target: { value: 'Cambridge, UK' } });
    fireEvent.change(screen.getByLabelText('Grade'), { target: { value: 'Distinction' } });
    fireEvent.change(screen.getByLabelText('Project description'), { target: { value: 'Updated pipeline' } });
    fireEvent.change(screen.getByLabelText('Certification credential ID'), { target: { value: 'AWS-99999' } });

    await userEvent.click(screen.getByRole('button', { name: 'Import selected' }));

    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledTimes(2));
    const confirmBody = JSON.parse((mockedApiFetch.mock.calls[1][1] as any).body);
    expect(confirmBody.experiences[0].location).toBe('Cambridge, UK');
    expect(confirmBody.education[0].grade).toBe('Distinction');
    expect(confirmBody.projects[0].summary).toBe('Updated pipeline');
    expect(confirmBody.certifications[0].credentialId).toBe('AWS-99999');
  });

  it('preserves year-only dates without inventing a month and sends year-only format', async () => {
    const yearOnlyResponse = {
      ...PREVIEW_RESPONSE,
      experiences: [
        {
          clientId: 'exp-year',
          data: {
            company: 'Acme Corp', title: 'Engineer', location: null,
            startMonth: '2021', endMonth: '2023',
            responsibilities: [], achievements: [], technologies: [],
            sourceVerified: true, requiresReview: false,
          },
          matchStatus: 'NEW',
          matchedExistingId: null,
        },
      ],
      skills: [],
      counts: { newCount: 1, existingCount: 0, possibleDuplicateCount: 0, conflictCount: 0, needsReviewCount: 0 },
    };
    mockedApiFetch.mockResolvedValueOnce(yearOnlyResponse as any);
    mockedApiFetch.mockResolvedValueOnce({ added: 1, replaced: 0, skipped: 0, warnings: [] } as any);
    render(React.createElement(ResumeImportWizard, { onClose: vi.fn(), onImported: vi.fn() }));
    await uploadFile();
    await screen.findByText(/1 new/);

    const startInput = screen.getByLabelText('Start month') as HTMLInputElement;
    expect(startInput.value).toBe('2021');

    await userEvent.click(screen.getByRole('button', { name: 'Import selected' }));

    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledTimes(2));
    const confirmBody = JSON.parse((mockedApiFetch.mock.calls[1][1] as any).body);
    expect(confirmBody.experiences[0].startMonth).toBe('2021');
  });
});
