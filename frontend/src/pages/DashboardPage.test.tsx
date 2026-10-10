// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { DashboardPage, describeFind } from './DashboardPage';

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
  company_name_raw: 'Acme',
  title: 'Backend Engineer',
  location_raw: 'London',
  remote_type: 'HYBRID' as const,
  employment_type: 'FULL_TIME',
  description_text: 'Java and Spring',
  skills_extracted: ['Java', 'Spring'],
  status: 'SCORED' as const,
  first_seen_at: '2026-10-01T10:00:00Z',
  last_seen_at: '2026-10-01T10:00:00Z',
  stale: false,
  source_name: 'Acme Careers',
  source_kind: 'GREENHOUSE',
  match_score: 78,
  match_recommendation: 'APPLY' as const,
};

const unscoredJob = { ...job, id: 'job-2', title: 'Data Engineer', match_score: null, match_recommendation: null };

const application = {
  id: 'application-1',
  jobId: 'job-1',
  status: 'READY_TO_APPLY',
  mode: 'ASSISTED',
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
  jobTitle: 'Backend Engineer',
  company: 'Acme',
};

const sources = [
  { id: 'source-1', kind: 'GREENHOUSE', org_identifier: 'acme', display_name: 'Acme Careers', policy: 'DISCOVERY_ONLY', rate_limit_per_min: 10, enabled: true, failure_streak: 0, last_run_at: null },
  { id: 'source-2', kind: 'ASHBY', org_identifier: 'beta', display_name: 'Beta Jobs', policy: 'DISCOVERY_ONLY', rate_limit_per_min: 10, enabled: true, failure_streak: 2, last_run_at: null },
  { id: 'source-3', kind: 'GREENHOUSE', org_identifier: 'off', display_name: 'Disabled', policy: 'DISCOVERY_ONLY', rate_limit_per_min: 10, enabled: false, failure_streak: 0, last_run_at: null },
];

function renderDashboard() {
  return render(React.createElement(MemoryRouter, null, React.createElement(DashboardPage)));
}

describe('DashboardPage workflow stages', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/system/health') return { status: 'UP' };
      if (path === '/sources') return sources;
      if (path === '/jobs?limit=100') return { items: [job, unscoredJob], next_cursor: '' };
      if (path === '/applications') return { items: [application] };
      if (path === '/review-queue?includePaused=true') {
        return { items: [{ decision: 'NEEDS_REVIEW' }, { decision: 'PAUSED' }], pendingCount: 1 };
      }
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('shows all four workflow stages using API-derived counts', async () => {
    renderDashboard();

    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    expect(screen.getByText('FIND')).toBeTruthy();
    expect(screen.getByText('PREP')).toBeTruthy();
    expect(screen.getByText('APPLY')).toBeTruthy();
    expect(screen.getByText('TRACK')).toBeTruthy();
    expect(screen.getByText(/Application records currently marked READY_TO_APPLY/)).toBeTruthy();
    expect(screen.getByText(/does not confirm documents were generated/)).toBeTruthy();
    expect(screen.getByText('Pending human-review items; approval does not submit an application')).toBeTruthy();
  });

  it('derives FIND relevance from the caller match and discovery status from the source registry', async () => {
    renderDashboard();
    // 1 of 2 loaded jobs has the caller's own APPLY/REVIEW match; 2 enabled boards, 1 failing.
    expect(await screen.findByText('1 recommended for you in 2 indexed jobs · 2 enabled boards, 1 with recent failures')).toBeTruthy();
    expect(screen.getByText('Match 78/100 · APPLY')).toBeTruthy();
    expect(screen.getByText('Not scored for you')).toBeTruthy();
  });

  it('does not interpret job API failure as an empty catalogue', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/system/health') return { status: 'UP' };
      if (path === '/sources') return sources;
      if (path === '/jobs?limit=100') throw new Error('API unavailable');
      if (path === '/applications') return { items: [] };
      if (path === '/review-queue?includePaused=true') return { items: [], pendingCount: 0 };
      throw new Error('Unexpected API path: ' + path);
    });

    renderDashboard();
    expect(await screen.findByText('Job listings are unavailable')).toBeTruthy();
    expect(screen.getAllByText(/API unavailable/).length).toBeGreaterThan(0);
    expect(screen.queryByText('No jobs in the current index')).toBeNull();
  });

  it('shows unavailable stages instead of zero when their APIs fail', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/system/health') return { status: 'UP' };
      if (path === '/sources') throw new Error('sources down');
      if (path === '/jobs?limit=100') return { items: [job], next_cursor: '' };
      if (path === '/applications') throw new Error('applications down');
      if (path === '/review-queue?includePaused=true') throw new Error('queue down');
      throw new Error('Unexpected API path: ' + path);
    });

    renderDashboard();
    expect(await screen.findByText(/Application metrics are unavailable: applications down/)).toBeTruthy();
    expect(screen.getByText(/Review queue metrics are unavailable: queue down/)).toBeTruthy();
    expect(screen.getAllByText('Unavailable').length).toBe(3);
    expect(screen.getByText(/Discovery sources unavailable/)).toBeTruthy();
  });
});

describe('describeFind', () => {
  it('scopes counts to the loaded page when more results exist', () => {
    expect(describeFind([job], true, null, [], null)).toBe(
      '1 recommended for you in the latest 1 indexed job · 0 enabled boards',
    );
  });

  it('reports a catalogue failure instead of a count', () => {
    expect(describeFind(null, false, 'boom', null, null)).toBe('Job catalogue unavailable: boom');
  });
});
