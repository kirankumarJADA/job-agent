// @vitest-environment jsdom
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiFetch } from '../api/client';
import { JobsFeedPage } from './JobsFeedPage';

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(),
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedApiFetch = vi.mocked(apiFetch);

const job = {
  id: 'job-1',
  source_id: 'source-1',
  external_id: 'ext-1',
  dedup_key: 'acme-backend',
  company_name_raw: 'Acme',
  title: 'Backend Engineer',
  location_raw: 'London',
  remote_type: 'HYBRID' as const,
  employment_type: 'FULL_TIME',
  description_text: 'Java and Spring',
  skills_extracted: ['Java', 'Spring'],
  status: 'DISCOVERED' as const,
  first_seen_at: '2026-10-01T10:00:00Z',
  last_seen_at: '2026-10-01T10:00:00Z',
};

const source = {
  id: 'source-1',
  kind: 'GREENHOUSE',
  org_identifier: 'acme',
  display_name: 'Acme Careers',
  policy: 'PUBLIC_ATS',
  rate_limit_per_min: 10,
  enabled: true,
  failure_streak: 0,
  last_run_at: '2026-10-01T10:00:00Z',
};

describe('JobsFeedPage FIND workflow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/sources') return [source];
      if (path.startsWith('/jobs?')) return { items: [job], next_cursor: '' };
      if (path === '/sources/source-1/health-check') {
        return {
          id: 'source-1',
          display_name: 'Acme Careers',
          kind: 'GREENHOUSE',
          enabled: true,
          failure_streak: 0,
          health: JSON.stringify({ status: 'ok', detail: '3 job(s) seen' }),
        };
      }
      if (path === '/jobs/import-url') return { status: 'RESOLUTION_PENDING' };
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('loads indexed jobs and shows an enabled supported source', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    expect(screen.getByText('Acme Careers')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Discover' })).toBeTruthy();
  });

  it('runs existing Greenhouse health-check discovery and refreshes the feed', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');
    await userEvent.click(screen.getByRole('button', { name: 'Discover' }));

    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledWith(
      '/sources/source-1/health-check', { method: 'POST' },
    ));
    expect(await screen.findByText('Acme Careers: discovery completed. 3 job(s) seen')).toBeTruthy();
    expect(mockedApiFetch.mock.calls.filter(([path]) => String(path).startsWith('/jobs?')).length).toBeGreaterThanOrEqual(2);
  });

  it('shows API failures instead of an empty-results message', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/sources') return [source];
      if (path.startsWith('/jobs?')) throw new Error('database is unavailable');
      throw new Error('Unexpected API path: ' + path);
    });
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    expect(await screen.findByText(/Job catalogue could not be loaded: database is unavailable/)).toBeTruthy();
    expect(screen.queryByText('No matching jobs')).toBeNull();
  });

  it('explains that URL validation does not yet import the job', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');
    await userEvent.type(screen.getByLabelText('Check a direct job URL'), 'https://example.com/jobs/123');
    await userEvent.click(screen.getByRole('button', { name: 'Validate URL' }));
    expect(await screen.findByText(/does not import a job into your feed/)).toBeTruthy();
  });
});
