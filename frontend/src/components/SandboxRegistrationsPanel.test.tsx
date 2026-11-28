// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { SandboxRegistrationsPanel } from './SandboxRegistrationsPanel';
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

const catalogResponse = (items: unknown[] = []) => ({
  configured: true,
  allowedDomains: ['boards.greenhouse.io'],
  allowedPathPrefixes: ['/robin-sandbox/'],
  requireHttps: true,
  items,
});

const activeRegistration = {
  id: 'reg-1',
  atsPlatform: 'GREENHOUSE',
  postingUrl: 'https://boards.greenhouse.io/robin-sandbox/jobs/42',
  permittedDomain: 'boards.greenhouse.io',
  approvedBy: 'candidate@example.local',
  approvedAt: '2026-11-24T20:00:00Z',
  revokedAt: null,
  active: true,
};

describe('SandboxRegistrationsPanel', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('registers a sandbox URL without sending caller-controlled trust fields and displays active item', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(catalogResponse([]))
      .mockResolvedValueOnce(activeRegistration)
      .mockResolvedValueOnce(catalogResponse([activeRegistration]));

    render(<SandboxRegistrationsPanel />);

    await waitFor(() => expect(screen.getByText(/Allowed domains:/i)).toBeTruthy());

    await userEvent.type(
      screen.getByLabelText('Canonical Sandbox Posting URL'),
      'https://boards.greenhouse.io/robin-sandbox/jobs/42',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Authorize Sandbox Posting' }));

    await waitFor(() =>
      expect(mockedApiFetch).toHaveBeenCalledWith('/sandbox-registrations', {
        method: 'POST',
        body: JSON.stringify({
          atsPlatform: 'GREENHOUSE',
          postingUrl: 'https://boards.greenhouse.io/robin-sandbox/jobs/42',
        }),
      }),
    );

    const postBody = JSON.parse(String(mockedApiFetch.mock.calls[1][1]?.body));
    expect(postBody.profileId).toBeUndefined();
    expect(postBody.permittedDomain).toBeUndefined();
    expect(postBody.approvedBy).toBeUndefined();

    await waitFor(() => expect(screen.getByText('Active')).toBeTruthy());
    expect(screen.getByText('https://boards.greenhouse.io/robin-sandbox/jobs/42')).toBeTruthy();
  });

  it('revokes an active registration while keeping history visible', async () => {
    const revokedRegistration = {
      ...activeRegistration,
      active: false,
      revokedAt: '2026-11-24T21:00:00Z',
    };
    mockedApiFetch
      .mockResolvedValueOnce(catalogResponse([activeRegistration]))
      .mockResolvedValueOnce(revokedRegistration)
      .mockResolvedValueOnce(catalogResponse([revokedRegistration]));

    render(<SandboxRegistrationsPanel />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Revoke' })).toBeTruthy());

    await userEvent.click(screen.getByRole('button', { name: 'Revoke' }));

    await waitFor(() =>
      expect(mockedApiFetch).toHaveBeenCalledWith('/sandbox-registrations/reg-1/revoke', {
        method: 'POST',
      }),
    );

    await waitFor(() => expect(screen.getByText('Revoked')).toBeTruthy());
  });

  it('shows disabled notice when server sandbox allowlist is not configured', async () => {
    mockedApiFetch.mockResolvedValueOnce({
      configured: false,
      allowedDomains: [],
      allowedPathPrefixes: [],
      requireHttps: true,
      items: [],
    });

    render(<SandboxRegistrationsPanel />);
    await waitFor(() =>
      expect(
        screen.getByText(/Sandbox registrations are disabled on this server/i),
      ).toBeTruthy(),
    );
  });

  it('rejects blank URL client-side, renders server 400/409 errors, and retries on load failure', async () => {
    // 1. Load error -> Retry recovers
    mockedApiFetch
      .mockRejectedValueOnce(new Error('Network offline'))
      .mockResolvedValueOnce(catalogResponse([]));

    render(<SandboxRegistrationsPanel />);
    await waitFor(() => expect(screen.getByText('Network offline')).toBeTruthy());
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.getByText(/Allowed domains:/i)).toBeTruthy());

    // 2. Blank URL triggers client validation without calling POST
    await userEvent.click(screen.getByRole('button', { name: 'Authorize Sandbox Posting' }));
    expect(screen.getByText(/Enter a canonical Greenhouse sandbox posting URL/i)).toBeTruthy();
    expect(mockedApiFetch).toHaveBeenCalledTimes(2);

    // 3. Server conflict (409) error message rendered visibly
    mockedApiFetch.mockRejectedValueOnce(new Error('HTTP 409: Sandbox URL already registered'));
    await userEvent.type(
      screen.getByLabelText('Canonical Sandbox Posting URL'),
      'https://boards.greenhouse.io/robin-sandbox/jobs/42',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Authorize Sandbox Posting' }));
    await waitFor(() => expect(screen.getByText(/Sandbox URL already registered/i)).toBeTruthy());
  });
});
