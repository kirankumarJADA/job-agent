// @vitest-environment jsdom
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ReviewQueuePage } from './ReviewQueuePage';
import { apiFetch } from '../api/client';

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

const items = {
  items: [
    {
      decisionId: 'd-1',
      jobId: 'job-1',
      jobTitle: 'Backend Engineer',
      companyName: 'Acme',
      location: 'London',
      applicationUrl: 'https://boards.greenhouse.io/acme/1',
      matchScore: 78,
      decision: 'NEEDS_REVIEW',
      reason: 'Score 78 in review range',
      createdAt: '2026-01-01T00:00:00Z',
    },
  ],
};

describe('ReviewQueuePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (String(path) === '/review-queue?includePaused=true') return { items: items.items, pendingCount: items.items.filter((item) => item.decision === 'NEEDS_REVIEW').length };
      if (String(path).includes('/review-queue/d-1')) {
        return { ...items.items[0], applicationId: null };
      }
      throw new Error('unexpected path ' + String(path));
    });
  });

  it('lists pending review items with score and reason', async () => {
    render(
      React.createElement(MemoryRouter, null, React.createElement(ReviewQueuePage)),
    );
    await waitFor(() => expect(screen.getByText('Backend Engineer')).toBeTruthy());
    expect(screen.getByText('Score 78/100')).toBeTruthy();
    expect(screen.getByText('Score 78 in review range')).toBeTruthy();

    // Details are collapsed by default; open them to reach the actions.
    await userEvent.click(screen.getByText('Inspect'));
    expect(await screen.findByText('Approve & create application')).toBeTruthy();
  });

  it('approving calls the approve endpoint and removes the item from the queue', async () => {
    mockedApiFetch.mockImplementation(async (path: string, init?: { method?: string }) => {
      if (String(path) === '/review-queue?includePaused=true') {
        return { items: items.items, pendingCount: items.items.filter((item) => item.decision === 'NEEDS_REVIEW').length };
      }
      if (String(path) === '/review-queue/d-1/approve') {
        return { application_id: 'app-1', created: true, status: 'READY_TO_APPLY' };
      }
      if (String(path).includes('/review-queue/d-1')) return { ...items.items[0], applicationId: null };
      throw new Error('unexpected ' + String(path));
    });

    render(
      React.createElement(MemoryRouter, null, React.createElement(ReviewQueuePage)),
    );
    await waitFor(() => expect(screen.getByText('Approve & create application')).toBeTruthy());

    await userEvent.click(screen.getByText('Approve & create application'));

    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledWith(
      '/review-queue/d-1/approve', { method: 'POST' },
    ));
    await waitFor(() => expect(screen.queryByText('Approve & create application')).toBeNull());
  });

  it('shows the empty state when the queue is clear', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (String(path) === '/review-queue?includePaused=true') return { items: [], pendingCount: 0 };
      throw new Error('unexpected ' + String(path));
    });

    render(
      React.createElement(MemoryRouter, null, React.createElement(ReviewQueuePage)),
    );
    await waitFor(() => expect(screen.getByText('Nothing needs your review')).toBeTruthy());
  });
});
