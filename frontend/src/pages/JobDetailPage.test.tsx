// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { cleanup, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { JobDetailPage } from './JobDetailPage';

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(),
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedApiFetch = vi.mocked(apiFetch);

const baseJob = {
  id: 'job-1',
  source_id: 'source-1',
  external_id: 'ext-1',
  company_name_raw: 'Acme',
  title: 'Backend Engineer',
  location_raw: null,
  remote_type: 'UNKNOWN',
  description_text: 'Build services in Java.',
  skills_extracted: ['Java'],
  status: 'SCORED',
  first_seen_at: '2026-09-01T10:00:00Z',
  last_seen_at: '2026-10-01T10:00:00Z',
  stale: false,
  removed: false,
  source_name: 'Acme Careers',
  source_kind: 'GREENHOUSE',
  match_score: 64,
  match_recommendation: 'REVIEW',
};

function detail(jobOverrides: Record<string, unknown> = {}, match: unknown = {
  score: 64,
  recommendation: 'REVIEW',
  breakdown: { skill_overlap: 70, remote_fit: 50, salary_fit: 50, why: 'Matched Java.' },
  scoredAt: '2026-10-01T10:00:00Z',
}) {
  return { job: { ...baseJob, ...jobOverrides }, analysis: null, score: null, match, decision_trace: [] };
}

function renderAt(path = '/jobs/job-1') {
  return render(
    React.createElement(MemoryRouter, { initialEntries: [path] },
      React.createElement(Routes, null,
        React.createElement(Route, { path: '/jobs/:id', element: React.createElement(JobDetailPage) }))),
  );
}

let jobResponse: unknown;

describe('JobDetailPage FIND detail', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    vi.clearAllMocks();
    jobResponse = detail();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/jobs/job-1') {
        if (jobResponse instanceof Error) throw jobResponse;
        return jobResponse;
      }
      if (path === '/cover-letters/job/job-1') return [];
      if (path === '/application-answers/job/job-1') return [];
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('shows the caller match from the API and missing posting data as missing', async () => {
    renderAt();
    expect(await screen.findByRole('heading', { name: 'Backend Engineer' })).toBeTruthy();
    expect(screen.getByText('64')).toBeTruthy();
    expect(screen.getByText('70%')).toBeTruthy();
    expect(screen.getByText('Matched Java.')).toBeTruthy();
    expect(screen.getByText('Not listed')).toBeTruthy();
    expect(screen.getByText('Not stated')).toBeTruthy();
    expect(screen.getByText('Salary not listed')).toBeTruthy();
    expect(screen.getByText('Not stated by the board')).toBeTruthy();
    expect(screen.getByText('Acme Careers (GREENHOUSE)')).toBeTruthy();
    for (const invented of ['United Kingdom', 'HYBRID', 'Competitive', 'Recently']) {
      expect(screen.queryByText(invented)).toBeNull();
    }
  });

  it('says plainly when the posting has not been scored for this candidate', async () => {
    jobResponse = detail({ match_score: null, match_recommendation: null }, null);
    renderAt();
    expect(await screen.findByText(/Not scored for you yet/)).toBeTruthy();
    expect(screen.queryByText('Create application anyway')).toBeNull();
  });

  it('warns that a stale posting will be refused by the review queue', async () => {
    jobResponse = detail({ stale: true });
    renderAt();
    expect(await screen.findByText(/Discovery has not seen this posting for 30\+ days/)).toBeTruthy();
  });

  it('flags a removed posting', async () => {
    jobResponse = detail({ removed: true });
    renderAt();
    expect(await screen.findByText(/This posting has been removed from the catalogue/)).toBeTruthy();
  });

  it('links onward only to existing review, application and preparation workflows', async () => {
    renderAt();
    await screen.findByRole('heading', { name: 'Backend Engineer' });
    expect(screen.getByRole('link', { name: 'APPLY · Open review queue' }).getAttribute('href')).toBe('/review-queue');
    expect(screen.getByRole('link', { name: 'TRACK · Open applications' }).getAttribute('href')).toBe('/applications');
    expect(screen.getByRole('link', { name: 'PREP · Documents for this job' }).getAttribute('href')).toBe('#application-package');
  });

  it('shows an API failure instead of an empty job', async () => {
    jobResponse = new Error('No job with that id');
    renderAt();
    expect(await screen.findByText('No job with that id')).toBeTruthy();
    expect(screen.getByRole('link', { name: /Back to Jobs Feed/ })).toBeTruthy();
  });
});
