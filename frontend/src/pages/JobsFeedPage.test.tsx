// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiFetch } from '../api/client';
import { AtsCapabilityRow } from '../types';
import { JobsFeedPage, feedSummary } from './JobsFeedPage';

afterEach(() => cleanup());

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(),
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedApiFetch = vi.mocked(apiFetch);

// Phase 10: a minimal, realistic stand-in for GET /ats-capabilities, reused
// by every describe block below so JobCard's capability pill has real data
// to classify against, exactly as it would in production.
const ATS_CAPABILITIES: AtsCapabilityRow[] = [
  { kind: 'GREENHOUSE', displayName: 'Greenhouse', hostPatterns: ['greenhouse.io'], summary: 'SUBMISSION_AVAILABLE', dimensions: {}, verificationNote: 'x' },
  { kind: 'LEVER', displayName: 'Lever', hostPatterns: ['jobs.lever.co', 'jobs.eu.lever.co'], summary: 'FORM_FILL_AVAILABLE', dimensions: {}, verificationNote: 'x' },
  { kind: 'ZOHO_RECRUIT', displayName: 'Zoho Recruit', hostPatterns: ['zohorecruit.com', 'zohorecruit.in'], summary: 'FORM_FILL_AVAILABLE', dimensions: {}, verificationNote: 'x' },
  { kind: 'WORKDAY', displayName: 'Workday', hostPatterns: ['myworkdayjobs.com'], summary: 'SIGN_IN_READINESS_ONLY', dimensions: {}, verificationNote: 'x' },
  { kind: 'GENERIC', displayName: 'Other recognised employer sites', hostPatterns: [], summary: 'MANUAL_APPLICATION_REQUIRED', dimensions: {}, verificationNote: 'x' },
];

/** Every test's dispatch function routes this path the same way — call it first in any mockImplementation. */
function routeSharedPaths(path: string): unknown | undefined {
  if (path === '/ats-capabilities') return ATS_CAPABILITIES;
  return undefined;
}

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
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
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
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
      if (path.startsWith('/jobs?')) throw new Error('database is unavailable');
      throw new Error('Unexpected API path: ' + path);
    });
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    expect(await screen.findByText(/Job catalogue could not be loaded: database is unavailable/)).toBeTruthy();
    expect(screen.queryByText('No matching jobs')).toBeNull();
  });

  it('surfaces explainable match highlights and matched skills on job cards', async () => {
    const jobWithBreakdown = {
      ...job,
      id: 'job-breakdown',
      title: 'Senior Cloud Architect',
      match_score: 92,
      match_recommendation: 'APPLY' as const,
      match_breakdown: {
        skill_overlap: 90,
        matched_skills: ['Java', 'Spring', 'Kubernetes'],
        highlights: ['Matches 3 required skills: Java, Spring, Kubernetes'],
      },
    };
    mockedApiFetch.mockImplementation(async (path: string) => {
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
      if (path.startsWith('/jobs?')) return { items: [jobWithBreakdown], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    expect(await screen.findByText('Senior Cloud Architect')).toBeTruthy();
    expect(screen.getByText('Matches:')).toBeTruthy();
    expect(screen.getByText('Kubernetes')).toBeTruthy();
    expect(screen.getByText('Matches 3 required skills: Java, Spring, Kubernetes')).toBeTruthy();
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
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
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

describe('JobsFeedPage Phase 9/10 apply-capability labels', () => {
  const assisted = { ...job, id: 'job-assisted', title: 'Assisted Role', application_url: 'https://boards.greenhouse.io/acme/jobs/123' };
  const formFill = { ...job, id: 'job-form-fill', title: 'Form Fill Role', application_url: 'https://jobs.lever.co/acme/x/apply' };
  const signIn = { ...job, id: 'job-sign-in', title: 'Sign-In Role', application_url: 'https://acme.wd5.myworkdayjobs.com/en-US/Site/job/x/apply' };
  const manual = { ...job, id: 'job-manual', title: 'Manual Role', application_url: 'https://careers.acme.com/jobs/123' };
  const unknown = { ...job, id: 'job-unknown', title: 'Unknown Role', application_url: undefined };

  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
      if (path.startsWith('/jobs?')) return { items: [assisted, formFill, signIn, manual, unknown], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('labels every job using the real backend capability model — distinct submission/form-fill/sign-in/manual labels — and never names a platform', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    await screen.findByText('Assisted Role');

    // Both SUBMISSION_AVAILABLE and FORM_FILL_AVAILABLE share the "Robin can help" label.
    expect(screen.getAllByText('Robin can help with this application')).toHaveLength(2);
    expect(screen.getByText('Robin can check your sign-in before you apply')).toBeTruthy();
    expect(screen.getByText("Apply on the employer's site")).toBeTruthy();
    expect(screen.queryByText(/greenhouse/i)).toBeNull();
    expect(screen.queryByText(/lever/i)).toBeNull();
    expect(screen.queryByText(/workday/i)).toBeNull();
  });

  it('shows no capability label at all when the capability fetch itself fails, rather than guessing', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/ats-capabilities') throw new Error('capabilities unavailable');
      if (path.startsWith('/jobs?')) return { items: [assisted], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    await screen.findByText('Assisted Role');

    expect(screen.queryByText('Robin can help with this application')).toBeNull();
    expect(screen.queryByText("Apply on the employer's site")).toBeNull();
  });
});

describe('JobsFeedPage Phase 9 discovery trigger', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
      if (path.startsWith('/jobs?')) return { items: [job], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('runs preference-driven discovery and shows an aggregate, source-free success summary, then refreshes the feed', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');
    mockedApiFetch.mockImplementation(async (path: string, options?: RequestInit) => {
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
      if (path === '/discovery/run-for-me' && options?.method === 'POST') {
        return { quotaExceeded: false, sourcesQueried: 3, sourcesSucceeded: 2, sourcesFailed: 1,
          sourcesRequireConfiguration: 0, newJobs: 5, updatedJobs: 2, totalListed: 40, notes: [] };
      }
      if (path.startsWith('/jobs?')) return { items: [job], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });

    await userEvent.click(screen.getByRole('button', { name: 'Find more jobs for me' }));

    expect(await screen.findByText(/Checked 3 sources: 5 new jobs found, 2 updated/)).toBeTruthy();
    expect(screen.getByText(/1 source\(s\) temporarily unreachable/)).toBeTruthy();
    expect(screen.queryByText(/greenhouse|lever|arbeitnow|adzuna|freehire/i)).toBeNull();
    expect(mockedApiFetch.mock.calls.some(([p]) => p === '/discovery/run-for-me')).toBe(true);
  });

  it('shows an honest, non-alarming message when the daily discovery quota is exhausted', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');
    mockedApiFetch.mockImplementation(async (path: string) => {
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
      if (path === '/discovery/run-for-me') {
        return { quotaExceeded: true, sourcesQueried: 0, sourcesSucceeded: 0, sourcesFailed: 0,
          sourcesRequireConfiguration: 0, newJobs: 0, updatedJobs: 0, totalListed: 0, notes: [] };
      }
      if (path.startsWith('/jobs?')) return { items: [job], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });

    await userEvent.click(screen.getByRole('button', { name: 'Find more jobs for me' }));

    expect(await screen.findByText(/discovery limit has been reached/)).toBeTruthy();
  });

  it('shows a clear error when the discovery request itself fails, never a fabricated success', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');
    mockedApiFetch.mockImplementation(async (path: string) => {
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
      if (path === '/discovery/run-for-me') throw new Error('upstream unavailable');
      if (path.startsWith('/jobs?')) return { items: [job], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });

    await userEvent.click(screen.getByRole('button', { name: 'Find more jobs for me' }));

    expect(await screen.findByText('upstream unavailable')).toBeTruthy();
  });
});

describe('feedSummary', () => {
  it('describes only what is loaded and never claims a total', () => {
    expect(feedSummary([], false)).toBe('0 jobs loaded · 0 scored for you · 0 recommended (APPLY or REVIEW)');
  });
});

describe('JobsFeedPage Phase 4 filters, sort and URL sync', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
      if (path.startsWith('/jobs?')) return { items: [job], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('typing a location filter debounces to a single request carrying the real backend param', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');
    mockedApiFetch.mockClear();

    const input = screen.getByLabelText('Filter by location');
    await userEvent.type(input, 'Berlin');

    // Debounced: typing six characters must not fire six requests.
    await new Promise((resolve) => setTimeout(resolve, 600));
    const locationCalls = mockedApiFetch.mock.calls.filter(([path]) => String(path).includes('location='));
    expect(locationCalls).toHaveLength(1);
    expect(locationCalls[0][0]).toContain('location=Berlin');
    expect(await screen.findByText('Location: Berlin')).toBeTruthy();
  });

  it('removing an active-filter chip clears exactly that filter and refetches without it', async () => {
    render(React.createElement(MemoryRouter, { initialEntries: ['/jobs?location=Berlin&remote_type=REMOTE'] },
      React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');
    expect(screen.getByText('Location: Berlin')).toBeTruthy();
    expect(screen.getByText('Workplace: REMOTE')).toBeTruthy();
    mockedApiFetch.mockClear();

    await userEvent.click(screen.getByRole('button', { name: 'Remove filter: Location: Berlin' }));

    await new Promise((resolve) => setTimeout(resolve, 600));
    expect(screen.queryByText('Location: Berlin')).toBeNull();
    expect(screen.getByText('Workplace: REMOTE')).toBeTruthy();
    const lastCall = mockedApiFetch.mock.calls.at(-1)?.[0] as string;
    expect(lastCall).not.toContain('location=');
    expect(lastCall).toContain('remote_type=REMOTE');
  });

  it('Reset all clears every active filter at once', async () => {
    render(React.createElement(MemoryRouter, { initialEntries: ['/jobs?location=Berlin&remote_type=REMOTE&sponsorship=POSSIBLE'] },
      React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');
    expect(screen.getByRole('button', { name: 'Reset all' })).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: 'Reset all' }));

    await new Promise((resolve) => setTimeout(resolve, 600));
    expect(screen.queryByText(/^Location:/)).toBeNull();
    expect(screen.queryByText(/^Workplace:/)).toBeNull();
    expect(screen.queryByText(/^Sponsorship:/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Reset all' })).toBeNull();
  });

  it('selecting "Best match first" sends sort=match to the backend-authoritative sort, never resorting client-side', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');
    mockedApiFetch.mockClear();

    await userEvent.selectOptions(screen.getByLabelText('Sort jobs by'), 'match');

    await new Promise((resolve) => setTimeout(resolve, 600));
    const lastCall = mockedApiFetch.mock.calls.at(-1)?.[0] as string;
    expect(lastCall).toContain('sort=match');
  });

  it('initializes filters from the URL on load, so a bookmarked filtered view reproduces the same request', async () => {
    render(React.createElement(MemoryRouter, { initialEntries: ['/jobs?location=Dublin&sort=posted&salary_min=50000'] },
      React.createElement(JobsFeedPage)));
    await screen.findByText('Backend Engineer');

    const call = mockedApiFetch.mock.calls.find(([path]) => String(path).includes('location='));
    expect(call?.[0]).toContain('location=Dublin');
    expect(call?.[0]).toContain('sort=posted');
    expect(call?.[0]).toContain('salary_min=50000');
    expect(screen.getByText('Location: Dublin')).toBeTruthy();
    expect(screen.getByText(/Salary ≥ 50000/)).toBeTruthy();
  });

  it('an empty result while a filter is active offers to reset filters rather than suggesting discovery is broken', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      const shared = routeSharedPaths(path);
      if (shared !== undefined) return shared;
      if (path.startsWith('/jobs?')) return { items: [], next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });
    render(React.createElement(MemoryRouter, { initialEntries: ['/jobs?location=Nowhere'] },
      React.createElement(JobsFeedPage)));

    expect(await screen.findByText('No matching jobs')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Reset all filters' })).toBeTruthy();
  });
});
