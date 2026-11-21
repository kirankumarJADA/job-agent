// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { SearchPlansPage } from './SearchPlansPage';
import * as client from '../api/client';
import type { ExpandedQueryPlan, SearchPlan, SearchPlanExecutionSummary } from '../types';

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    fetchSearchPlans: vi.fn(),
    fetchSearchPlan: vi.fn(),
    createSearchPlan: vi.fn(),
    updateSearchPlan: vi.fn(),
    deleteSearchPlan: vi.fn(),
    runSearchPlan: vi.fn(),
    expandSearchPlan: vi.fn(),
  };
});

const mockedFetchSearchPlans = vi.mocked(client.fetchSearchPlans);
const mockedCreateSearchPlan = vi.mocked(client.createSearchPlan);
const mockedUpdateSearchPlan = vi.mocked(client.updateSearchPlan);
const mockedDeleteSearchPlan = vi.mocked(client.deleteSearchPlan);
const mockedRunSearchPlan = vi.mocked(client.runSearchPlan);
const mockedExpandSearchPlan = vi.mocked(client.expandSearchPlan);

const mockPlan1: SearchPlan = {
  id: 'sp-11111111-1111-1111-1111-111111111111',
  name: 'UK Backend Senior Search',
  targetTitles: ['Senior Java Engineer', 'Backend Tech Lead'],
  targetSources: ['ADZUNA', 'GREENHOUSE', 'LEVER'],
  searchFrequency: 'DAILY',
  maxResultsPerRun: 40,
  active: true,
  lastRunAt: '2026-11-21T10:00:00Z',
  lastExecutionStatus: 'SUCCESS',
  lastExecutionError: null,
  lastExecutionResults: 12,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-21T10:00:00Z',
  queryFacets: {
    keywords: ['Java', 'Spring Boot'],
    excludeKeywords: ['PHP'],
    locations: ['London, UK'],
    remoteTypes: ['REMOTE', 'HYBRID'],
    seniorityLevels: ['SENIOR'],
  },
};

const mockPlan2: SearchPlan = {
  id: 'sp-22222222-2222-2222-2222-222222222222',
  name: 'Engineering Manager Search',
  targetTitles: ['Engineering Manager'],
  targetSources: ['ARBEITNOW'],
  searchFrequency: 'WEEKLY',
  maxResultsPerRun: 25,
  active: false,
  lastRunAt: null,
  lastExecutionStatus: null,
  lastExecutionError: null,
  lastExecutionResults: 0,
  createdAt: '2026-11-19T10:00:00Z',
  updatedAt: '2026-11-19T10:00:00Z',
  queryFacets: {},
};

function renderPage() {
  return render(
    <MemoryRouter>
      <SearchPlansPage />
    </MemoryRouter>
  );
}

describe('SearchPlansPage', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders loading state initially', () => {
    mockedFetchSearchPlans.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText('Loading search plans…')).toBeTruthy();
  });

  it('renders empty state when no plans exist', async () => {
    mockedFetchSearchPlans.mockResolvedValueOnce([]);
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('No search plans configured')).toBeTruthy();
    });
    expect(screen.getByText('Create your first search plan')).toBeTruthy();
  });

  it('renders search plans list with details, status, and facets', async () => {
    mockedFetchSearchPlans.mockResolvedValueOnce([mockPlan1, mockPlan2]);
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('UK Backend Senior Search')).toBeTruthy();
      expect(screen.getByText('Engineering Manager Search')).toBeTruthy();
    });

    // Check Plan 1 badges and metadata
    expect(screen.getByText('Active')).toBeTruthy();
    expect(screen.getByText('DAILY')).toBeTruthy();
    expect(screen.getByText('Senior Java Engineer')).toBeTruthy();
    expect(screen.getByText('Backend Tech Lead')).toBeTruthy();
    expect(screen.getByText('ADZUNA')).toBeTruthy();
    expect(screen.getByText('GREENHOUSE')).toBeTruthy();
    expect(screen.getByText('LEVER')).toBeTruthy();
    expect(screen.getByText('SUCCESS')).toBeTruthy();
    expect(screen.getByText('(12 results)')).toBeTruthy();

    // Check Plan 2 badges
    expect(screen.getByText('Paused')).toBeTruthy();
    expect(screen.getByText('WEEKLY')).toBeTruthy();
    expect(screen.getByText('Not run yet')).toBeTruthy();
  });

  it('toggles plan active status', async () => {
    mockedFetchSearchPlans.mockResolvedValueOnce([mockPlan1]);
    mockedUpdateSearchPlan.mockResolvedValueOnce({
      ...mockPlan1,
      active: false,
    });

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('UK Backend Senior Search')).toBeTruthy();
    });

    const pauseBtn = screen.getByRole('button', { name: 'Pause' });
    fireEvent.click(pauseBtn);

    await waitFor(() => {
      expect(mockedUpdateSearchPlan).toHaveBeenCalledWith(
        mockPlan1.id,
        expect.objectContaining({ active: false })
      );
    });

    expect(await screen.findByText('Plan "UK Backend Senior Search" is now Paused.')).toBeTruthy();
  });

  it('opens Create modal and creates a new plan successfully', async () => {
    mockedFetchSearchPlans.mockResolvedValueOnce([mockPlan1]);
    const newPlan: SearchPlan = {
      id: 'sp-33333333-3333-3333-3333-333333333333',
      name: 'Fullstack React Search',
      targetTitles: ['Fullstack Engineer'],
      targetSources: ['ADZUNA', 'ARBEITNOW'],
      searchFrequency: 'DAILY',
      maxResultsPerRun: 30,
      active: true,
      lastRunAt: null,
      lastExecutionStatus: null,
      lastExecutionError: null,
      lastExecutionResults: 0,
      createdAt: '2026-11-21T12:00:00Z',
      updatedAt: '2026-11-21T12:00:00Z',
      queryFacets: {
        keywords: ['TypeScript', 'React'],
      },
    };
    mockedCreateSearchPlan.mockResolvedValueOnce(newPlan);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('UK Backend Senior Search')).toBeTruthy();
    });

    const createBtn = screen.getByRole('button', { name: /Create Search Plan/i });
    fireEvent.click(createBtn);

    expect(screen.getByRole('heading', { name: 'Create Search Plan' })).toBeTruthy();

    const nameInput = screen.getByLabelText(/Plan Name \*/i);
    fireEvent.change(nameInput, { target: { value: 'Fullstack React Search' } });

    const titlesInput = screen.getByLabelText(/Target Roles & Titles \*/i);
    fireEvent.change(titlesInput, { target: { value: 'Fullstack Engineer' } });

    const submitBtn = screen.getByRole('button', { name: 'Create Plan' });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(mockedCreateSearchPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Fullstack React Search',
          targetTitles: ['Fullstack Engineer'],
        })
      );
    });

    expect(await screen.findByText('Search plan "Fullstack React Search" created successfully.')).toBeTruthy();
    expect(screen.getByText('Fullstack React Search')).toBeTruthy();
  });

  it('executes Run Now and displays execution summary banner with provider transparency', async () => {
    mockedFetchSearchPlans.mockResolvedValueOnce([mockPlan1]);
    const summary: SearchPlanExecutionSummary = {
      searchPlanId: mockPlan1.id,
      planName: mockPlan1.name,
      status: 'SUCCESS',
      sourcesQueried: 3,
      sourcesSucceeded: 3,
      sourcesFailed: 0,
      jobsIngested: 8,
      jobsMatched: 8,
      errors: [],
      notes: ['Adzuna: retrieved 8 results, facet negative keywords handled downstream'],
    };
    mockedRunSearchPlan.mockResolvedValueOnce(summary);
    mockedFetchSearchPlans.mockResolvedValueOnce([
      {
        ...mockPlan1,
        lastExecutionStatus: 'SUCCESS',
        lastExecutionResults: 8,
        lastRunAt: '2026-11-21T14:40:00Z',
      },
    ]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('UK Backend Senior Search')).toBeTruthy();
    });

    const runNowBtn = screen.getByRole('button', { name: /Run Now/i });
    fireEvent.click(runNowBtn);

    await waitFor(() => {
      expect(mockedRunSearchPlan).toHaveBeenCalledWith(mockPlan1.id);
    });

    // Verify execution summary banner appears
    expect(await screen.findByText('Search Plan Execution Run')).toBeTruthy();
    expect(screen.getByText('3 OK · 0 Failed')).toBeTruthy();
    expect(screen.getByText('Adzuna: retrieved 8 results, facet negative keywords handled downstream')).toBeTruthy();

    // Dismiss banner
    const dismissBtn = screen.getByRole('button', { name: 'Dismiss' });
    fireEvent.click(dismissBtn);
    expect(screen.queryByText('Search Plan Execution Run')).toBeNull();
  });

  it('expands query plan and renders Preview Expansion modal', async () => {
    mockedFetchSearchPlans.mockResolvedValueOnce([mockPlan1]);
    const expanded: ExpandedQueryPlan = {
      searchPlanId: mockPlan1.id,
      planName: mockPlan1.name,
      originalTitles: ['Senior Java Engineer', 'Backend Tech Lead'],
      expandedTitles: [
        'Senior Java Engineer',
        'Senior Java Developer',
        'Senior Backend Engineer',
        'Backend Tech Lead',
        'Technical Lead - Backend',
      ],
      primaryKeywords: ['Java', 'Spring Boot'],
      negativeKeywords: ['PHP'],
      targetLocations: ['London, UK'],
      remoteTypes: ['REMOTE', 'HYBRID'],
      seniorityLevels: ['SENIOR'],
      targetSources: ['ADZUNA', 'GREENHOUSE', 'LEVER'],
      maxResults: 40,
    };
    mockedExpandSearchPlan.mockResolvedValueOnce(expanded);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('UK Backend Senior Search')).toBeTruthy();
    });

    const expandBtn = screen.getByRole('button', { name: 'Preview Expansion' });
    fireEvent.click(expandBtn);

    await waitFor(() => {
      expect(mockedExpandSearchPlan).toHaveBeenCalledWith(mockPlan1.id);
    });

    expect(await screen.findByText('Query Expander Analysis')).toBeTruthy();
    expect(screen.getByText('Senior Java Developer')).toBeTruthy();
    expect(screen.getByText('Technical Lead - Backend')).toBeTruthy();
    expect(screen.getByText('Generated Title Synonyms (5):')).toBeTruthy();

    const closeBtn = screen.getByRole('button', { name: 'Close' });
    fireEvent.click(closeBtn);
    expect(screen.queryByText('Query Expander Analysis')).toBeNull();
  });

  it('deletes a plan after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    mockedFetchSearchPlans.mockResolvedValueOnce([mockPlan1]);
    mockedDeleteSearchPlan.mockResolvedValueOnce(undefined);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('UK Backend Senior Search')).toBeTruthy();
    });

    const deleteBtn = screen.getByRole('button', { name: 'Delete search plan' });
    fireEvent.click(deleteBtn);

    await waitFor(() => {
      expect(mockedDeleteSearchPlan).toHaveBeenCalledWith(mockPlan1.id);
    });

    expect(await screen.findByText('Search plan "UK Backend Senior Search" deleted.')).toBeTruthy();
    expect(screen.queryByText('UK Backend Senior Search')).toBeNull();
  });
});
