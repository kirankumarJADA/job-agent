// @vitest-environment jsdom
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { DashboardPage } from './DashboardPage';

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

describe('DashboardPage workflow stages', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/system/health') return { status: 'UP' };
      if (path === '/jobs?limit=100') return { items: [job], next_cursor: '' };
      if (path === '/applications') return { items: [application] };
      if (path === '/review-queue?includePaused=true') {
        return { items: [{ decision: 'NEEDS_REVIEW' }, { decision: 'PAUSED' }], pendingCount: 1 };
      }
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('shows all four workflow stages using API-derived counts', async () => {
    render(React.createElement(MemoryRouter, null, React.createElement(DashboardPage)));

    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    expect(screen.getByText('FIND')).toBeTruthy();
    expect(screen.getByText('PREP')).toBeTruthy();
    expect(screen.getByText('APPLY')).toBeTruthy();
    expect(screen.getByText('TRACK')).toBeTruthy();
    expect(screen.getByText('Application records currently marked READY_TO_APPLY')).toBeTruthy();
    expect(screen.getByText('Pending human-review items; approval does not submit an application')).toBeTruthy();
  });

  it('does not interpret job API failure as an empty catalogue', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/system/health') return { status: 'UP' };
      if (path === '/jobs?limit=100') throw new Error('API unavailable');
      if (path === '/applications') return { items: [] };
      if (path === '/review-queue?includePaused=true') return { items: [], pendingCount: 0 };
      throw new Error('Unexpected API path: ' + path);
    });

    render(React.createElement(MemoryRouter, null, React.createElement(DashboardPage)));
    expect(await screen.findByText('Job listings are unavailable')).toBeTruthy();
    expect(screen.getByText('API unavailable')).toBeTruthy();
  });
});
