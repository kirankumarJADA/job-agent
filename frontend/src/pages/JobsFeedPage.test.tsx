// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiFetch } from '../api/client';
import { JobsFeedPage, feedSummary } from './JobsFeedPage';

afterEach(() => cleanup());

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
    expect(await screen.findByText(/This action did not import a job into your feed/)).toBeTruthy();
    expect(screen.queryByText(/imported successfully/i)).toBeNull();
  });
});

describe('JobsFeedPage FIND data fidelity', () => {
  const scored = {
    ...job,
    id: 'job-scored',
    title: 'Platform Engineer',
    salary_min: 60000,
    salary_max: 80000,
    salary_currency: 'GBP',
    source_name: 'Acme Careers',
    match_score: 81,
    match_recommendation: 'APPLY' as const,
    stale: false,
  };
  const staleUnscored = {
    ...job,
    id: 'job-stale',
    title: 'Legacy Engineer',
    location_raw: undefined,
    match_score: null,
    match_recommendation: null,
    stale: true,
  };
  const nextPage = { ...job, id: 'job-page-2', title: 'Second Page Engineer', match_score: 55, match_recommendation: 'REVIEW' as const };

  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/sources') return [source, { ...source, id: 'source-off', display_name: 'Disabled Board', enabled: false }];
      if (path.startsWith('/jobs?') && path.includes('cursor=c1')) return { items: [nextPage], next_cursor: '' };
      if (path.startsWith('/jobs?')) return { items: [scored, staleUnscored], next_cursor: 'c1' };
      if (path === '/sources/source-1/health-check') {
        return { id: 'source-1', health: { status: 'failed', error: 'greenhouse:UPSTREAM_503' } };
      }
      throw new Error('Unexpected API path: ' + path);
    });
  });

  function renderFeed() {
    return render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
  }

  it('shows the caller match, staleness, source and posted salary without inventing values', async () => {
    renderFeed();
    expect(await screen.findByText('Platform Engineer')).toBeTruthy();
    expect(screen.getByText('Match 81/100 · APPLY')).toBeTruthy();
    expect(screen.getByText('Not scored for you')).toBeTruthy();
    expect(screen.getByText('Not seen for 30+ days')).toBeTruthy();
    expect(screen.getByText('GBP 60,000 – GBP 80,000')).toBeTruthy();
    expect(screen.getAllByText('via Acme Careers').length).toBeGreaterThan(0);
    // A missing location is shown as missing, never as an assumed country.
    expect(screen.getByText('Location not listed')).toBeTruthy();
    expect(screen.queryByText('United Kingdom')).toBeNull();
    expect(screen.queryByText('Competitive')).toBeNull();
    expect(screen.getByText(/2 jobs loaded · 1 scored for you · 1 recommended \(APPLY or REVIEW\) · 1 not seen for 30\+ days · more available/)).toBeTruthy();
  });

  it('loads the next page with the API cursor and appends it', async () => {
    renderFeed();
    await screen.findByText('Platform Engineer');
    await userEvent.click(screen.getByRole('button', { name: 'Load more jobs' }));

    expect(await screen.findByText('Second Page Engineer')).toBeTruthy();
    expect(mockedApiFetch).toHaveBeenCalledWith('/jobs?limit=50&cursor=c1');
    expect(screen.getByText('Platform Engineer')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Load more jobs' })).toBeNull();
  });

  it('reports a failed board fetch as a failure, not as completed discovery', async () => {
    renderFeed();
    await screen.findByText('Platform Engineer');
    const buttons = screen.getAllByRole('button', { name: 'Discover' });
    await userEvent.click(buttons[0]);

    expect(await screen.findByText(/Acme Careers: discovery reported a failure\. greenhouse:UPSTREAM_503/)).toBeTruthy();
    expect(screen.queryByText(/discovery completed/)).toBeNull();
  });

  it('never lets a disabled source run discovery', async () => {
    renderFeed();
    await screen.findByText('Disabled Board');
    const buttons = screen.getAllByRole('button', { name: 'Discover' }) as HTMLButtonElement[];
    expect(buttons).toHaveLength(2);
    expect(buttons[1].disabled).toBe(true);
  });

  it('surfaces a rate-limited discovery call as an error', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/sources') return [source];
      if (path.startsWith('/jobs?')) return { items: [scored], next_cursor: '' };
      if (path === '/sources/source-1/health-check') throw new Error('Too many requests. Try again in 60 seconds.');
      throw new Error('Unexpected API path: ' + path);
    });
    renderFeed();
    await screen.findByText('Platform Engineer');
    await userEvent.click(screen.getByRole('button', { name: 'Discover' }));
    expect(await screen.findByText(/Acme Careers: Too many requests/)).toBeTruthy();
  });
});

describe('feedSummary', () => {
  it('describes only what is loaded and never claims a total', () => {
    expect(feedSummary([], false)).toBe('0 jobs loaded · 0 scored for you · 0 recommended (APPLY or REVIEW)');
  });
});
