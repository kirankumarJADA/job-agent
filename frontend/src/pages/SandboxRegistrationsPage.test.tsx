// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { SandboxRegistrationsPage } from './SandboxRegistrationsPage';

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

describe('SandboxRegistrationsPage', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('mounts cleanly for /sandbox-registrations route', async () => {
    mockedApiFetch.mockResolvedValueOnce({
      configured: true,
      allowedDomains: ['boards.greenhouse.io'],
      allowedPathPrefixes: ['/robin-sandbox/'],
      requireHttps: true,
      items: [],
    });

    render(<SandboxRegistrationsPage />);

    expect(screen.getByText('Authorized Sandbox Postings')).toBeTruthy();
    expect(await screen.findByText('No sandbox postings authorized yet')).toBeTruthy();
  });
});
