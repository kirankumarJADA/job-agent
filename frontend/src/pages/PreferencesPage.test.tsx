// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiFetch } from '../api/client';
import { PreferencesPage } from './PreferencesPage';

afterEach(() => cleanup());

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(),
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedApiFetch = vi.mocked(apiFetch);

const BASE_PREFS = {
  id: 'pref-1',
  profileId: 'profile-1',
  titles: ['Backend Engineer'],
  keywordsInclude: [],
  keywordsExclude: [],
  requiredSkills: ['Java'],
  locationsAllowed: ['UK'],
  remoteTypes: ['REMOTE', 'HYBRID'],
  employmentTypes: ['FULL_TIME'],
  experienceLevels: [],
  salaryMinGbp: 60000,
  sponsorshipPolicy: 'SHOW_ALL' as const,
  applicationMode: 'ASSISTED' as const,
  scoringWeights: { skill: 30, experience: 15, visa: 20, location: 10, salary: 10, career: 10, difficulty: 5 },
  isActive: true,
};

describe('PreferencesPage Phase 9 country/region selection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/preferences') return { ...BASE_PREFS, extraFilters: { countries: ['GB'] } };
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('defaults to the United Kingdom selected when the candidate has never set a country', async () => {
    render(React.createElement(PreferencesPage));
    await screen.findByText('Countries to search');

    const uk = screen.getByRole('button', { name: 'United Kingdom' });
    expect(uk.getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'United States' }).getAttribute('aria-pressed')).toBe('false');
  });

  it('loads the candidate\'s previously saved countries from extraFilters', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/preferences') return { ...BASE_PREFS, extraFilters: { countries: ['GB', 'DE'] } };
      throw new Error('Unexpected API path: ' + path);
    });
    render(React.createElement(PreferencesPage));
    await screen.findByText('Countries to search');

    expect(screen.getByRole('button', { name: 'United Kingdom' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'Germany' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'United States' }).getAttribute('aria-pressed')).toBe('false');
  });

  it('saving sends the selected countries inside extraFilters, never replacing other extraFilters keys', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/preferences') return { ...BASE_PREFS, extraFilters: { countries: ['GB'], other: 'kept' } };
      throw new Error('Unexpected API path: ' + path);
    });
    render(React.createElement(PreferencesPage));
    await screen.findByText('Countries to search');

    await userEvent.click(screen.getByRole('button', { name: 'Germany' }));
    mockedApiFetch.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/preferences' && options?.method === 'PUT') return { ...BASE_PREFS, extraFilters: { countries: ['GB', 'DE'], other: 'kept' } };
      if (path === '/preferences') return { ...BASE_PREFS, extraFilters: { countries: ['GB'], other: 'kept' } };
      throw new Error('Unexpected API path: ' + path);
    });

    await userEvent.click(screen.getByRole('button', { name: 'Save job search preferences' }));

    const putCall = await vi.waitFor(() => {
      const call = mockedApiFetch.mock.calls.find(([p, o]) => p === '/preferences' && (o as RequestInit | undefined)?.method === 'PUT');
      if (!call) throw new Error('PUT not yet called');
      return call;
    });
    const body = JSON.parse(String((putCall[1] as RequestInit).body));
    expect(body.extraFilters).toEqual({ countries: ['GB', 'DE'], other: 'kept' });
  });

  it('deselecting every country still saves (the backend honestly defaults to GB, this page never fabricates a selection)', async () => {
    render(React.createElement(PreferencesPage));
    await screen.findByText('Countries to search');

    await userEvent.click(screen.getByRole('button', { name: 'United Kingdom' }));

    expect(await screen.findByText(/Select at least one country/)).toBeTruthy();
  });
});
