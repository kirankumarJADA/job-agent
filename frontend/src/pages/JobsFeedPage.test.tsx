// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen } from '@testing-library/react';
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

describe('JobsFeedPage FIND workflow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path.startsWith('/jobs?')) return { items: [job], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('loads indexed jobs without fetching or showing any discovery source', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    // Phase 8.2.5: running discovery and source health moved to Job Sources (operations).
    expect(mockedApiFetch.mock.calls.some(([path]) => String(path).startsWith('/sources'))).toBe(false);
    expect(screen.queryByRole('button', { name: 'Discover' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Manage discovery' })).toBeTruthy();
  });

  it('shows API failures instead of an empty-results message', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path.startsWith('/jobs?')) throw new Error('database is unavailable');
      throw new Error('Unexpected API path: ' + path);
    });
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    expect(await screen.findByText(/Job catalogue could not be loaded: database is unavailable/)).toBeTruthy();
    expect(screen.queryByText('No matching jobs')).toBeNull();
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
      if (path.startsWith('/jobs?') && path.includes('cursor=c1')) return { items: [nextPage], next_cursor: '' };
      if (path.startsWith('/jobs?')) return { items: [scored, staleUnscored], next_cursor: 'c1' };
      throw new Error('Unexpected API path: ' + path);
    });
  });

  function renderFeed() {
    return render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
  }

  it('shows the caller match, staleness and posted salary without inventing values or naming a source', async () => {
    renderFeed();
    expect(await screen.findByText('Platform Engineer')).toBeTruthy();
    expect(screen.getByText('Match 81/100 · APPLY')).toBeTruthy();
    expect(screen.getByText('Not scored for you')).toBeTruthy();
    expect(screen.getByText('Not seen for 30+ days')).toBeTruthy();
    expect(screen.getByText('GBP 60,000 – GBP 80,000')).toBeTruthy();
    expect(screen.queryByText(/^via /)).toBeNull();
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

});

describe('feedSummary', () => {
  it('describes only what is loaded and never claims a total', () => {
    expect(feedSummary([], false)).toBe('0 jobs loaded · 0 scored for you · 0 recommended (APPLY or REVIEW)');
  });
});
