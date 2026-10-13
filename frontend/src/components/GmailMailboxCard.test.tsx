// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { GmailMailboxCard } from './GmailMailboxCard';

vi.mock('../api/client', () => ({ apiFetch: vi.fn(), API_BASE: 'https://api.example.test/api/v1' }));

const mocked = vi.mocked(apiFetch);

const connectedStatus = {
  configured: true,
  connected: true,
  accountEmail: 'candidate@example.com',
  scopes: 'https://www.googleapis.com/auth/gmail.readonly',
  status: 'CONNECTED',
};

describe('GmailMailboxCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.history.pushState({}, '', '/applications');
  });
  afterEach(() => cleanup());

  it.each([
    ['connected', /Gmail connected \(read-only\)/],
    ['refused', /Gmail access was not granted, so nothing was connected/],
    ['invalid_state', /expired or did not start from this session/],
    ['failed', /Gmail could not be connected\. Nothing was stored/],
  ])('explains the OAuth callback result "%s" with fixed wording', async (result, message) => {
    mocked.mockResolvedValue({ configured: true, connected: result === 'connected' });
    window.history.pushState({}, '', `/applications?track&gmail=${result}`);
    render(<GmailMailboxCard />);
    expect(await screen.findByText(message)).toBeTruthy();
  });

  it('never echoes an unrecognised callback value into the page', async () => {
    mocked.mockResolvedValue({ configured: true, connected: false });
    window.history.pushState({}, '', '/applications?track&gmail=%3Cimg%20src%3Dx%3E%20sign%20in%20again');
    const { container } = render(<GmailMailboxCard />);
    expect(await screen.findByRole('button', { name: /Connect Gmail \(read-only\)/ })).toBeTruthy();
    expect(container.textContent).not.toContain('sign in again');
    expect(container.querySelector('img')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('tells the page to refresh tracking after a successful sync', async () => {
    mocked.mockImplementation((async (path: string) => {
      if (path === '/integrations/gmail/status') return connectedStatus;
      if (path === '/integrations/gmail/sync') return { status: 'OK', ingested: 2, considered: 3 };
      throw new Error(`Unexpected API call: ${path}`);
    }) as never);
    const onChanged = vi.fn();
    render(<GmailMailboxCard onChanged={onChanged} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Sync now' }));
    expect(await screen.findByText(/Sync finished: 2 new message/)).toBeTruthy();
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(mocked).toHaveBeenCalledWith('/integrations/gmail/sync', { method: 'POST' });
  });

  it('does not report a refresh when the sync request fails', async () => {
    mocked.mockImplementation((async (path: string) => {
      if (path === '/integrations/gmail/status') return connectedStatus;
      throw new Error('Gmail rejected the stored credentials. Disconnect and connect again.');
    }) as never);
    const onChanged = vi.fn();
    render(<GmailMailboxCard onChanged={onChanged} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Sync now' }));
    expect(await screen.findByText(/Disconnect and connect again/)).toBeTruthy();
    expect(onChanged).not.toHaveBeenCalled();
  });
});
