// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { cleanup, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { JobsFeedPage } from './JobsFeedPage';
import { JobDetailPage } from './JobDetailPage';
import { JobCard } from '../components/ui';
import { ApplicationTrackPanel } from '../components/ApplicationTrackPanel';
import { TrackReviewQueue } from '../components/TrackReviewQueue';
import { ApplicationsPage } from './ApplicationsPage';
import type { Job } from '../types';

/**
 * Source hiding regression (Phase 8.2.5). Jobs discovered through several
 * different sources — an ATS board, an enterprise ATS and the Tsenta
 * aggregator — are rendered through every candidate surface with
 * provider-revealing data deliberately present in the payload (as an older
 * API or a future regression might send it). None of it may reach the DOM:
 * not as text, labels, titles, aria attributes or badges. Employer, title and
 * the genuine apply link must still be visible.
 */

afterEach(() => cleanup());

vi.mock('../api/client', () => {
  class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  }
  return { apiFetch: vi.fn(), setIdTokenProvider: vi.fn(), API_BASE: 'http://test/api/v1', ApiError };
});

const mockedApiFetch = vi.mocked(apiFetch);

const PROVIDER_PATTERNS = [/greenhouse/i, /workday/i, /tsenta/i, /smartrecruiters/i, /powered by/i, /found on/i, /\bvia\b/i,
  /source:/i, /aggregator/i];

type LeakyJob = Job & Record<string, unknown>;

function leaky(id: string, title: string, employer: string, applyUrl: string, source: Record<string, string>): LeakyJob {
  return {
    id, title, company_name_raw: employer, location_raw: 'London', remote_type: 'HYBRID', employment_type: 'FULL_TIME',
    description_text: 'Role description.', skills_extracted: ['Java'], status: 'DISCOVERED',
    first_seen_at: '2026-10-01T10:00:00Z', last_seen_at: '2026-10-02T10:00:00Z', application_url: applyUrl,
    match_score: 70, match_recommendation: 'APPLY', ...source,
  } as LeakyJob;
}

const jobs: LeakyJob[] = [
  leaky('job-gh', 'Backend Engineer', 'Stripe', 'https://stripe.com/jobs/listing/1/apply',
    { source_name: 'Stripe (Greenhouse)', source_kind: 'GREENHOUSE', canonical_url: 'https://boards.greenhouse.io/stripe/jobs/1' }),
  leaky('job-wd', 'GPU Engineer', 'NVIDIA', 'https://nvidia.example.com/apply/JR1',
    { source_name: 'NVIDIA (Workday)', source_kind: 'WORKDAY', canonical_url: 'https://nvidia.wd5.myworkdayjobs.com/x' }),
  leaky('job-ts', 'English Tutor', 'Sehyog Academy', 'https://sehyog.zohorecruit.in/jobs/Careers/167689000000570023/Tutor',
    { source_name: 'Tsenta', source_kind: 'TSENTA', canonical_url: 'https://tsenta.com/jobs/sehyog-tutor-8d7f73a6' }),
  leaky('job-sr', 'Operador', 'Bosch Group', 'https://bosch.example.com/apply/744000154830479',
    { source_name: 'Bosch (SmartRecruiters)', source_kind: 'SMARTRECRUITERS', canonical_url: 'https://jobs.smartrecruiters.com/BoschGroup/1' }),
];

function assertNoProviderLeak(container: HTMLElement) {
  const text = container.textContent || '';
  const attributes = Array.from(container.querySelectorAll('*')).flatMap((element) =>
    Array.from(element.attributes)
      .filter((attribute) => attribute.name !== 'href') // the employer's own apply link is allowed
      .map((attribute) => attribute.value));
  for (const pattern of PROVIDER_PATTERNS) {
    expect(text, `visible text matched ${pattern}`).not.toMatch(pattern);
    for (const value of attributes) expect(value, `attribute matched ${pattern}`).not.toMatch(pattern);
  }
  for (const element of Array.from(container.querySelectorAll('a[href]'))) {
    const href = element.getAttribute('href') || '';
    expect(href).not.toMatch(/tsenta\.com/i);
  }
}

describe('candidate surfaces never reveal the discovery source', () => {
  beforeEach(() => vi.clearAllMocks());

  it('job cards show employer and title but no source label, for every source', () => {
    const { container } = render(React.createElement(MemoryRouter, null,
      ...jobs.map((job) => React.createElement(JobCard, { key: job.id, job }))));
    for (const job of jobs) {
      expect(screen.getByText(job.title)).toBeTruthy();
      expect(screen.getByText(job.company_name_raw as string)).toBeTruthy();
    }
    assertNoProviderLeak(container);
  });

  it('the feed page shows no source text, badge or label', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path.startsWith('/jobs?')) return { items: jobs, next_cursor: '' };
      throw new Error('Unexpected API path: ' + path);
    });
    const { container } = render(React.createElement(MemoryRouter, null, React.createElement(JobsFeedPage)));
    expect(await screen.findByText('English Tutor')).toBeTruthy();
    assertNoProviderLeak(container);
  });

  it.each(jobs.map((job) => [job.id, job] as const))('job detail %s shows employer, title and the genuine apply link only', async (_id, job) => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === `/jobs/${job.id}`) return { job, analysis: null, score: null, match: null, decision_trace: [] };
      if (path.startsWith('/cover-letters/job/') || path.startsWith('/application-answers/job/')) return [];
      if (path.startsWith('/prep/jobs/')) {
        return {
          jobId: job.id, applicationId: null, applicationStatus: null, overall: 'NOT_STARTED', overallLabel: 'Not started',
          cv: { state: 'MISSING' }, coverLetter: { state: 'NOT_GENERATED', requirement: 'UNKNOWN', versions: 0 },
          answers: { total: 0, confirmed: 0, unconfirmed: 0, needsInput: 0, requiredQuestionsKnown: false },
          blockers: [], actions: [], notChecked: [], note: '', computedAt: '2026-10-10T00:00:00Z',
        };
      }
      if (path.startsWith('/resume-intelligence/job/')) return { cv: null };
      throw new Error('Unexpected API path: ' + path);
    });
    const { container } = render(React.createElement(MemoryRouter, { initialEntries: [`/jobs/${job.id}`] },
      React.createElement(Routes, null, React.createElement(Route, { path: '/jobs/:id', element: React.createElement(JobDetailPage) }))));
    expect(await screen.findByRole('heading', { name: job.title })).toBeTruthy();
    expect(screen.getAllByText(job.company_name_raw as string).length).toBeGreaterThan(0);
    const apply = screen.getByRole('link', { name: /Apply with the employer/ });
    expect(apply.getAttribute('href')).toBe(job.application_url);
    assertNoProviderLeak(container);
  });
});

/**
 * TRACK (Phase 8.3) carries the same rule onto the application surfaces: the
 * timeline may say whether something came from Robin, the candidate or the
 * employer, but never which board, ATS or aggregator supplied the job — even
 * when an older or regressed payload carries that provenance.
 */
describe('TRACK surfaces never reveal the discovery source', () => {
  beforeEach(() => vi.clearAllMocks());

  const leakyFields = {
    source_name: 'Stripe (Greenhouse)', source_kind: 'GREENHOUSE', discoverySource: 'Tsenta',
    provider: 'Workday', canonical_url: 'https://boards.greenhouse.io/stripe/jobs/1', aggregator: 'Tsenta',
  };

  const leakyTrack = {
    applicationId: 'app-1', availability: 'AVAILABLE', ...leakyFields,
    application: { applicationId: 'app-1', jobTitle: 'Backend Engineer', company: 'Stripe', location: 'London', createdAt: '2026-10-01T09:00:00Z', ...leakyFields },
    status: 'ACKNOWLEDGED', label: 'Acknowledged by the employer — not verified', basis: 'UNVERIFIED', basisLabel: 'unverified employer evidence',
    reason: 'An employer acknowledgement identifies this application, but it was added by you, so its sender could not be authenticated.',
    since: '2026-10-03T10:00:00Z', sincePrecision: 'EXACT', evidenceStage: 'ACKNOWLEDGED', recordedStatus: 'READY_TO_APPLY',
    recordedStatusLabel: 'Ready to apply',
    submission: { state: 'UNVERIFIED', label: 'Not verified', reason: 'An employer acknowledgement identifies this application but could not be verified.' },
    acknowledgement: { state: 'UNVERIFIED', label: 'Acknowledgement not verified' },
    communications: { linkedMessages: 1, awaitingReview: 0 }, pending: null, attention: [], unknowns: [], conflicts: [],
    needsReview: false, robinSubmission: { submitted: false, note: 'Robin has not submitted this application: real submission is disabled.' },
    receipts: [{
      id: 'rc-1', evidenceType: 'EMPLOYER_ACKNOWLEDGEMENT_EMAIL', evidenceLabel: 'Employer acknowledgement evidence',
      channel: 'CANDIDATE_FORWARDED', channelLabel: 'Added by you', verification: 'UNVERIFIED', verificationLabel: 'Not verified',
      reasons: [{ code: 'CHANNEL_NOT_AUTHENTICATED', message: 'The message was added by you, so its sender cannot be authenticated.' }],
      policyVersion: 'track-receipt-policy-v1', summary: 'Employer acknowledgement email', externalReference: null,
      evidenceAt: '2026-10-03T10:00:00Z', evidenceAtPrecision: 'EXACT', capturedAt: '2026-10-03T10:00:00Z', candidateReview: null,
      candidateReviewedAt: null, reviewVersion: 0, emailId: 'em-1', ...leakyFields,
    }],
    reviews: [], mailbox: { connected: false, note: 'Robin cannot read your mailbox.' }, computedAt: '2026-10-11T10:00:00Z',
  };

  const leakyTimeline = {
    items: [
      { id: 'e1', type: 'GREENHOUSE_PLAN_CREATED', summary: 'Form-filling plan prepared — nothing is sent to the employer',
        classification: 'INTERNAL', verification: 'NOT_APPLICABLE', label: 'Robin activity', actor: 'ROBIN',
        occurredAt: '2026-10-02T09:00:00Z', occurredOn: null, occurredAtPrecision: 'EXACT', recordedAt: '2026-10-02T09:00:00Z',
        supersedes: null, receiptId: null, emailId: null, ...leakyFields },
      { id: 'e2', type: 'RECEIPT_RECORDED', summary: 'Employer acknowledgement recorded — not verified', classification: 'EXTERNAL',
        verification: 'UNVERIFIED', label: 'Employer evidence — unverified', actor: 'EMPLOYER', occurredAt: '2026-10-03T10:00:00Z',
        occurredOn: null, occurredAtPrecision: 'EXACT', recordedAt: '2026-10-03T10:00:00Z', supersedes: null, receiptId: 'rc-1',
        emailId: 'em-1', ...leakyFields },
    ],
    nextCursor: '',
    limit: 20,
  };

  const leakyReview = {
    emailId: 'em-2', status: 'PENDING_REVIEW', reviewType: 'ASSOCIATION', subject: 'Your application', from: 'talent@stripe.example',
    receivedAt: '2026-10-04T09:00:00Z', receivedTimeKnown: true, category: 'RECRUITER_FOLLOW_UP', categoryLabel: 'Recruiter follow-up',
    categoryConfirmed: false, confidence: 0.6, confidenceNote: 'A rule-strength score — not proof of anything.', excerpt: 'Following up on next steps.',
    rationale: [{ code: 'WEAK_SIGNALS_ONLY', message: 'Only the company name matches.' }], decisionVersion: 0, addedBy: 'YOU',
    currentApplication: null,
    candidates: [{ applicationId: 'app-1', jobTitle: 'Backend Engineer', company: 'Stripe', location: 'London', signals: ['Company name matches'], ...leakyFields }],
    allowedCategories: [{ value: 'RECRUITER_FOLLOW_UP', label: 'Recruiter follow-up' }], ...leakyFields,
  };

  function serveTrack() {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/applications') {
        return { items: [{
          id: 'app-1', jobId: 'job-gh', status: 'READY_TO_APPLY', mode: 'ASSISTED', createdAt: '2026-10-01T09:00:00Z',
          updatedAt: '2026-10-01T09:00:00Z', jobTitle: 'Backend Engineer', company: 'Stripe', jobLocation: 'London',
          matchScore: 70, matchRecommendation: 'APPLY', planId: null, planStatus: null, ...leakyFields,
          track: { availability: 'AVAILABLE', status: 'ACKNOWLEDGED', label: 'Acknowledged by the employer — not verified',
            basis: 'UNVERIFIED', submission: 'UNVERIFIED', needsReview: false, attentionCount: 0, pending: null, ...leakyFields },
        }] };
      }
      if (path === '/track/reviews') return { items: [leakyReview], mailboxConnected: false };
      if (path === '/applications/app-1/track') return leakyTrack;
      if (path.startsWith('/applications/app-1/track/timeline')) return leakyTimeline;
      if (path.startsWith('/prep/jobs/') || path.startsWith('/apply/applications/')) {
        const { ApiError } = await import('../api/client');
        throw new ApiError(503, JSON.stringify({ message: 'Not loaded in this test.' }));
      }
      throw new Error('Unexpected API path: ' + path);
    });
  }

  it('the tracking panel shows evidence channels but no discovery source', async () => {
    serveTrack();
    const { container } = render(React.createElement(ApplicationTrackPanel, { applicationId: 'app-1', defaultOpen: true }));
    expect(await screen.findByText('Employer acknowledgement recorded — not verified')).toBeTruthy();
    expect(screen.getAllByText('Employer acknowledgement evidence').length).toBeGreaterThan(0);
    assertNoProviderLeak(container);
  });

  it('the review queue shows the employer but no discovery source', async () => {
    serveTrack();
    const { container } = render(React.createElement(TrackReviewQueue));
    expect(await screen.findByText('Your application')).toBeTruthy();
    expect(screen.getAllByText(/Stripe/).length).toBeGreaterThan(0);
    assertNoProviderLeak(container);
  });

  it('the applications page shows employer and title but no discovery source', async () => {
    serveTrack();
    const { container } = render(React.createElement(MemoryRouter, null, React.createElement(ApplicationsPage)));
    expect((await screen.findAllByText('Backend Engineer')).length).toBeGreaterThan(0);
    expect(await screen.findByText('Your application')).toBeTruthy();
    assertNoProviderLeak(container);
  });
});
