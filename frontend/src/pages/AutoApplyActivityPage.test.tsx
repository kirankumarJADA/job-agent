// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { AutoApplyActivityPage } from './AutoApplyActivityPage';
import { apiFetch } from '../api/client';
import type { ApprovalActivity } from '../types';

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

const activity = (over: Partial<ApprovalActivity> = {}): ApprovalActivity => ({
  today: { considered: 3, byDecision: { AUTO_APPLY: 2, NEEDS_REVIEW: 1 } },
  last7Days: { considered: 10, byDecision: { AUTO_APPLY: 6, NEEDS_REVIEW: 3, SKIP: 1 } },
  dailyApplicationQuota: { used: 2, limit: 25, remaining: 23 },
  ...over,
});

function renderPage() {
  return render(React.createElement(AutoApplyActivityPage));
}

describe('AutoApplyActivityPage', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loads and displays today and last-7-days activity, plus quota usage', async () => {
    mockedApiFetch.mockResolvedValueOnce(activity());
    renderPage();

    expect(screen.getByText('Loading Auto Apply activity…')).toBeTruthy();

    await waitFor(() => expect(screen.getByText('Auto Apply Activity')).toBeTruthy());
    expect(mockedApiFetch).toHaveBeenCalledWith('/approval-rules/activity');

    expect(screen.getAllByText('3').length).toBeGreaterThan(0); // today considered
    expect(screen.getByText('10')).toBeTruthy(); // last 7 days considered
    expect(screen.getByText('2 / 25')).toBeTruthy();
    expect(screen.getByText('23 left today')).toBeTruthy();
    // "Auto-applied" and "Sent to review" each appear once per window (today + last 7 days).
    expect(screen.getAllByText('Auto-applied')).toHaveLength(2);
    expect(screen.getAllByText('Sent to review')).toHaveLength(2);
  });

  it('renders an empty-window message when nothing was considered', async () => {
    mockedApiFetch.mockResolvedValueOnce(activity({
      today: { considered: 0, byDecision: {} },
    }));
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto Apply Activity')).toBeTruthy());
    expect(screen.getByText('No matches were considered in this window.')).toBeTruthy();
  });

  it('shows a warning tone once the daily quota is nearly exhausted', async () => {
    mockedApiFetch.mockResolvedValueOnce(activity({
      dailyApplicationQuota: { used: 24, limit: 25, remaining: 1 },
    }));
    renderPage();

    await waitFor(() => expect(screen.getByText('1 left today')).toBeTruthy());
  });

  it('shows a load error without crashing when the fetch fails', async () => {
    mockedApiFetch.mockRejectedValueOnce(new Error('network down'));
    renderPage();

    await waitFor(() => expect(screen.getByText('network down')).toBeTruthy());
  });

  it('refreshing re-fetches the activity endpoint', async () => {
    mockedApiFetch.mockResolvedValueOnce(activity());
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto Apply Activity')).toBeTruthy());

    mockedApiFetch.mockResolvedValueOnce(activity({ today: { considered: 5, byDecision: {} } }));
    await userEvent.click(screen.getByText('Refresh'));

    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledTimes(2));
  });
});
