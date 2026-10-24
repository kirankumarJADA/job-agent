// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiFetch } from '../api/client';
import { SourcesPage, healthDisplay } from './SourcesPage';
import type { JobSource, PlatformCoverage } from '../types';

afterEach(() => cleanup());

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(),
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedApiFetch = vi.mocked(apiFetch);

const source: JobSource = {
  id: 'source-1',
  kind: 'GREENHOUSE',
  org_identifier: 'acme',
  display_name: 'Acme Careers',
  policy: 'DISCOVERY_ONLY',
  rate_limit_per_min: 30,
  enabled: true,
  failure_streak: 0,
  last_run_at: '2026-10-01T10:00:00Z',
  health: '{}',
  last_run: '{}',
};

const coverageRow = (kind: string, name: string, adapter: boolean, status: PlatformCoverage['status']): PlatformCoverage => ({
  kind, name, group: 'A', aliases: [], readiness: adapter ? 'LIVE_VALIDATED' : 'UNVERIFIED', adapter, status,
  configuration: 'board format', configured_sources: 0, enabled_sources: 0, failing_sources: 0, last_success_at: null,
});

const coverage = [
  coverageRow('GREENHOUSE', 'Greenhouse', true, 'SUPPORTED'),
  coverageRow('WORKDAY', 'Workday', true, 'NEEDS_BOARD_CONFIGURATION'),
  coverageRow('BRASSRING', 'BrassRing', false, 'BLOCKED'),
  coverageRow('DOVER', 'Dover', false, 'UNVERIFIED'),
];

function renderPage() {
  return render(React.createElement(MemoryRouter, null, React.createElement(SourcesPage)));
}

describe('SourcesPage operations view', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/sources') return [source, { ...source, id: 'source-off', display_name: 'Disabled Board', enabled: false }];
      if (path === '/sources/coverage') return coverage;
      if (path === '/sources/source-1/health-check') {
        return { id: 'source-1', health: JSON.stringify({ status: 'ok', detail: '3 listed, 3 new' }) };
      }
      throw new Error('Unexpected API path: ' + path);
    });
  });

  it('never shows a source as healthy unless its recorded state says so', async () => {
    renderPage();
    expect(await screen.findByText('Acme Careers')).toBeTruthy();
    expect(screen.queryByText('Healthy')).toBeNull();
    expect(screen.getByText('Not checked yet')).toBeTruthy();
    expect(screen.getByText('Disabled')).toBeTruthy();
  });

  it('runs real discovery through the health check and reports the recorded outcome', async () => {
    renderPage();
    await screen.findByText('Acme Careers');
    await userEvent.click(screen.getByRole('button', { name: 'Run discovery for Acme Careers' }));
    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledWith('/sources/source-1/health-check', { method: 'POST' }));
    expect(await screen.findByText('Acme Careers: discovery completed. 3 listed, 3 new')).toBeTruthy();
  });

  it('reports a failed board fetch as a failure, not as completed discovery', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/sources') return [source];
      if (path === '/sources/coverage') return coverage;
      if (path === '/sources/source-1/health-check') return { id: 'source-1', health: { status: 'failed', error: 'UPSTREAM_ERROR: HTTP 503' } };
      throw new Error('Unexpected API path: ' + path);
    });
    renderPage();
    await screen.findByText('Acme Careers');
    await userEvent.click(screen.getByRole('button', { name: 'Run discovery for Acme Careers' }));
    expect(await screen.findByText(/Acme Careers: discovery reported a failure\. UPSTREAM_ERROR: HTTP 503/)).toBeTruthy();
    expect(screen.queryByText(/discovery completed/)).toBeNull();
  });

  it('never lets a disabled source or an unsupported platform run discovery', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/sources') {
        return [
          { ...source, id: 'source-off', display_name: 'Disabled Board', enabled: false },
          { ...source, id: 'source-br', kind: 'BRASSRING', display_name: 'Blocked Board' },
        ];
      }
      if (path === '/sources/coverage') return coverage;
      throw new Error('Unexpected API path: ' + path);
    });
    renderPage();
    await screen.findByText('Disabled Board');
    expect((screen.getByRole('button', { name: 'Run discovery for Disabled Board' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Run discovery for Blocked Board' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('surfaces a rate-limited discovery call as an error', async () => {
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/sources') return [source];
      if (path === '/sources/coverage') return coverage;
      if (path === '/sources/source-1/health-check') throw new Error('Too many requests. Try again in 60 seconds.');
      throw new Error('Unexpected API path: ' + path);
    });
    renderPage();
    await screen.findByText('Acme Careers');
    await userEvent.click(screen.getByRole('button', { name: 'Run discovery for Acme Careers' }));
    expect(await screen.findByText(/Acme Careers: Too many requests/)).toBeTruthy();
  });

  it('shows every platform with its honest coverage status', async () => {
    renderPage();
    expect(await screen.findByText('BrassRing')).toBeTruthy();
    expect(screen.getByText('BLOCKED')).toBeTruthy();
    expect(screen.getByText('UNVERIFIED')).toBeTruthy();
    expect(screen.getByText('NEEDS BOARD CONFIGURATION')).toBeTruthy();
  });

  it('checks a URL, previews the resolved board and only configures it when confirmed', async () => {
    const calls: Array<Record<string, unknown>> = [];
    mockedApiFetch.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path === '/sources') return [source];
      if (path === '/sources/coverage') return coverage;
      if (path === '/jobs/import-url') {
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
        calls.push(body);
        if (body.confirm) {
          return { status: 'CONFIGURED', source_id: 'new', run: { status: 'ok', complete: true, counts: { listed: 2, created: 2 } } };
        }
        return {
          status: 'RESOLVED', board: 'stripe', platform: { kind: 'GREENHOUSE', name: 'Greenhouse', readiness: 'LIVE_VALIDATED', board_format: 'token' },
          preview: { listed_on_first_page: 2, more_pages_possible: false, sample_titles: ['Backend Engineer'] },
        };
      }
      throw new Error('Unexpected API path: ' + path);
    });
    renderPage();
    await screen.findByText('Acme Careers');
    await userEvent.type(screen.getByLabelText('Public careers or job URL'), 'https://boards.greenhouse.io/stripe');
    await userEvent.click(screen.getByRole('button', { name: 'Check URL' }));
    expect(await screen.findByText(/First page: 2 posting\(s\)/)).toBeTruthy();
    expect(calls[0]).toMatchObject({ url: 'https://boards.greenhouse.io/stripe', preview: true });
    expect(calls[0].confirm).toBeUndefined();

    await userEvent.click(screen.getByRole('button', { name: 'Add board and run discovery' }));
    expect(await screen.findByText(/Board added\. First run: ok — 2 listed, 2 new/)).toBeTruthy();
    expect(calls[1]).toMatchObject({ confirm: true });
  });

  it('explains an unsupported platform and a rejected URL instead of pretending to import', async () => {
    mockedApiFetch.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path === '/sources') return [source];
      if (path === '/sources/coverage') return coverage;
      if (path === '/jobs/import-url') {
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
        if (String(body.url).includes('localhost')) throw new Error('This URL cannot be fetched: internal host names are refused');
        return { status: 'UNSUPPORTED', message: 'BrassRing is blocked in this version, so it cannot be followed yet.' };
      }
      throw new Error('Unexpected API path: ' + path);
    });
    renderPage();
    await screen.findByText('Acme Careers');
    const input = screen.getByLabelText('Public careers or job URL');
    await userEvent.type(input, 'https://sjobs.brassring.com/x');
    await userEvent.click(screen.getByRole('button', { name: 'Check URL' }));
    expect(await screen.findByText(/BrassRing is blocked/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add board and run discovery' })).toBeNull();

    await userEvent.clear(input);
    await userEvent.type(input, 'https://localhost/admin');
    await userEvent.click(screen.getByRole('button', { name: 'Check URL' }));
    expect(await screen.findByText(/internal host names are refused/)).toBeTruthy();
  });

  it('toggles a source enabled state via PATCH /sources/:id', async () => {
    mockedApiFetch.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path === '/sources') return [source];
      if (path === '/sources/coverage') return coverage;
      if (path === '/sources/source-1' && init?.method === 'PATCH') {
        return { ...source, enabled: false };
      }
      throw new Error('Unexpected API path: ' + path);
    });
    renderPage();
    await screen.findByText('Acme Careers');
    const toggleBtn = screen.getByRole('button', { name: 'Toggle Acme Careers' });
    expect(toggleBtn.textContent).toBe('Disable');
    await userEvent.click(toggleBtn);
    await waitFor(() => expect(mockedApiFetch).toHaveBeenCalledWith(
      '/sources/source-1',
      expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ enabled: false }) })
    ));
    expect(await screen.findByText('Acme Careers is now disabled.')).toBeTruthy();
  });

  it('displays operator access required message when API responds with 403 Forbidden', async () => {
    mockedApiFetch.mockImplementation(async () => {
      throw new Error('403 Forbidden: Operator access required');
    });
    renderPage();
    expect(await screen.findByText('Operator Access Required')).toBeTruthy();
    expect(screen.getByText(/restricted to authorized platform operators/i)).toBeTruthy();
  });
});

describe('healthDisplay', () => {
  it('maps recorded health honestly', () => {
    expect(healthDisplay({ ...source, health: { status: 'ok' } }).label).toBe('Last run OK');
    expect(healthDisplay({ ...source, health: '{"status":"failed"}', failure_streak: 3 }).label).toBe('Failing (3)');
    expect(healthDisplay({ ...source, health: '{"status":"degraded"}' }).label).toBe('Last run partial');
    expect(healthDisplay({ ...source, health: 'not json' }).label).toBe('Not checked yet');
    expect(healthDisplay({ ...source, policy: 'DISABLED' }).label).toBe('Disabled');
  });
});
