// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { AutoApplyPage } from './AutoApplyPage';
import { apiFetch } from '../api/client';
import type { AutoApplyPreview, AutoApplyRunDetail, AutoApplyRunSummary, AutoApplySettings } from '../types';

vi.mock('../api/client', () => ({
  ApiError: class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  },
  apiFetch: vi.fn(),
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedApiFetch = vi.mocked(apiFetch);

function settings(over: Partial<AutoApplySettings> = {}): AutoApplySettings {
  return {
    enabled: false,
    configured: false,
    minScore: 70,
    dailyCap: 10,
    maxBatchSize: 25,
    remoteType: null,
    location: null,
    employmentType: null,
    salaryMin: null,
    ...over,
  };
}

function runSummary(over: Partial<AutoApplyRunSummary> = {}): AutoApplyRunSummary {
  return {
    id: 'run-1',
    status: 'COMPLETED',
    jobsConsidered: 5,
    jobsEligible: 3,
    prepared: 2,
    skippedDuplicate: 1,
    skippedThreshold: 2,
    skippedMissingProfile: 0,
    skippedUnsupportedFlow: 0,
    quotaLimited: 0,
    failures: 0,
    startedAt: '2026-10-20T10:00:00Z',
    completedAt: '2026-10-20T10:00:05Z',
    error: null,
    ...over,
  };
}

function renderPage() {
  return render(React.createElement(AutoApplyPage));
}

describe('AutoApplyPage', () => {
  afterEach(() => cleanup());
  beforeEach(() => vi.clearAllMocks());

  it('loads current settings and run history on mount', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/auto-apply/settings') return settings({ configured: true, enabled: true, minScore: 75 });
      if (path.startsWith('/auto-apply/runs')) return { items: [runSummary()] };
      throw new Error('Unexpected path: ' + path);
    });

    renderPage();

    await waitFor(() => expect(screen.getByText('Auto Apply')).toBeTruthy());
    expect(screen.getByDisplayValue('75')).toBeTruthy();
    expect(screen.getByText('Enabled')).toBeTruthy();
    await waitFor(() => expect(screen.getByText(/2 prepared/)).toBeTruthy());
  });

  it('a freshly-loaded unconfigured form cannot be saved until something changes', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/auto-apply/settings') return settings();
      if (path.startsWith('/auto-apply/runs')) return { items: [] };
      throw new Error('Unexpected path: ' + path);
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto Apply')).toBeTruthy());

    const saveButton = screen.getByRole('button', { name: /Save Auto Apply settings/ }) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(true);
  });

  it('saving sends the current settings and reports success', async () => {
    mockedApiFetch.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path === '/auto-apply/settings' && (!init || init.method === undefined)) return settings();
      if (path.startsWith('/auto-apply/runs')) return { items: [] };
      if (path === '/auto-apply/settings' && init?.method === 'PUT') {
        const body = JSON.parse(String(init.body));
        return settings({ ...body, configured: true });
      }
      throw new Error('Unexpected path: ' + path);
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto Apply')).toBeTruthy());

    const toggle = screen.getByRole('switch', { name: 'Toggle Auto Apply batch preparation' });
    await userEvent.click(toggle);

    const saveButton = screen.getByRole('button', { name: /Save Auto Apply settings/ });
    await userEvent.click(saveButton);

    await waitFor(() => expect(screen.getByText('Auto Apply settings saved.')).toBeTruthy());
    const putCall = mockedApiFetch.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === 'PUT');
    expect(putCall).toBeTruthy();
    const body = JSON.parse(String((putCall?.[1] as RequestInit).body));
    expect(body.enabled).toBe(true);
  });

  it('the Start run button is disabled while the feature is disabled', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/auto-apply/settings') return settings({ enabled: false });
      if (path.startsWith('/auto-apply/runs')) return { items: [] };
      throw new Error('Unexpected path: ' + path);
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto Apply')).toBeTruthy());

    expect((screen.getByRole('button', { name: /Start run now/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/Enable and save batch preparation above/)).toBeTruthy();
  });

  it('preview shows predicted outcomes without starting a run', async () => {
    const preview: AutoApplyPreview = {
      jobsConsidered: 2,
      jobsEligible: 1,
      items: [
        { jobId: 'job-1', jobTitle: 'Backend Engineer', companyName: 'Acme', matchScore: 90, predictedOutcome: 'WOULD_PREPARE', detail: null },
        { jobId: 'job-2', jobTitle: 'Frontend Engineer', companyName: 'Acme', matchScore: 40, predictedOutcome: 'SKIPPED_THRESHOLD', detail: 'Below threshold' },
      ],
    };
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/auto-apply/settings') return settings({ enabled: true, configured: true });
      if (path.startsWith('/auto-apply/runs')) return { items: [] };
      if (path === '/auto-apply/preview') return preview;
      throw new Error('Unexpected path: ' + path);
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto Apply')).toBeTruthy());

    await userEvent.click(screen.getByRole('button', { name: /Preview eligible jobs/ }));

    await waitFor(() => expect(screen.getByText(/2 jobs considered, 1 at or above/)).toBeTruthy());
    expect(screen.getByText(/Backend Engineer/)).toBeTruthy();
    // No run was started by previewing.
    expect(mockedApiFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'POST')).toHaveLength(0);
  });

  it('starting a run reports the real outcome and never claims submission', async () => {
    mockedApiFetch.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path === '/auto-apply/settings') return settings({ enabled: true, configured: true });
      if (path.startsWith('/auto-apply/runs') && (!init || init.method === undefined)) return { items: [] };
      if (path === '/auto-apply/runs' && init?.method === 'POST') return runSummary({ prepared: 3, jobsEligible: 4, jobsConsidered: 6 });
      throw new Error('Unexpected path: ' + path);
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto Apply')).toBeTruthy());

    await userEvent.click(screen.getByRole('button', { name: /Start run now/ }));

    await waitFor(() => expect(screen.getByText(/3 prepared, 4 eligible of 6 considered/)).toBeTruthy());
    expect(screen.getByText(/still need your review/)).toBeTruthy();
  });

  it('a disabled-batch refusal from the server is shown, not silently retried', async () => {
    mockedApiFetch.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path === '/auto-apply/settings') return settings({ enabled: true, configured: true });
      if (path.startsWith('/auto-apply/runs') && (!init || init.method === undefined)) return { items: [] };
      if (path === '/auto-apply/runs' && init?.method === 'POST') {
        const { ApiError } = await import('../api/client');
        throw new ApiError(409, 'Auto Apply batch preparation is disabled.');
      }
      throw new Error('Unexpected path: ' + path);
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto Apply')).toBeTruthy());

    await userEvent.click(screen.getByRole('button', { name: /Start run now/ }));

    await waitFor(() => expect(screen.getByText('Auto Apply batch preparation is disabled.')).toBeTruthy());
  });

  it('expanding a run from history shows per-job outcomes and review links', async () => {
    const detail: AutoApplyRunDetail = {
      ...runSummary(),
      items: [
        { jobId: 'job-1', jobTitle: 'Backend Engineer', companyName: 'Acme', applicationId: 'app-1', matchScore: 90, outcome: 'PREPARED', detail: null, createdAt: '2026-10-20T10:00:01Z' },
      ],
    };
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/auto-apply/settings') return settings({ enabled: true, configured: true });
      if (path === '/auto-apply/runs?limit=10') return { items: [runSummary()] };
      if (path === '/auto-apply/runs/run-1') return detail;
      throw new Error('Unexpected path: ' + path);
    });
    renderPage();
    await waitFor(() => expect(screen.getByText(/2 prepared/)).toBeTruthy());

    await userEvent.click(screen.getByText(/2 prepared/));

    await waitFor(() => expect(screen.getByText(/Backend Engineer/)).toBeTruthy());
    expect(screen.getByText('Go to Review Queue')).toBeTruthy();
    expect(screen.getByText('Go to Applications')).toBeTruthy();
  });
});
