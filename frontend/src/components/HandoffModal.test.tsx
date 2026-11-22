// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { HandoffModal } from './HandoffModal';
import * as client from '../api/client';
import type { HandoffSession } from '../types';

vi.mock('../api/client', () => ({
  completeHandoff: vi.fn(),
  cancelHandoff: vi.fn(),
}));

describe('HandoffModal', () => {
  const mockSession: HandoffSession = {
    id: 'test-session-123',
    planId: 'test-plan-456',
    applicationId: 'test-app-789',
    profileId: 'test-profile-000',
    status: 'PENDING',
    blockReason: 'CAPTCHA',
    targetUrl: 'https://jobs.lever.co/example/apply',
    handoffToken: 'test-token-abc',
    expiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };

  const onClose = vi.fn();
  const onResumed = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders modal with title, block reason, and instructions', () => {
    render(<HandoffModal session={mockSession} onClose={onClose} onResumed={onResumed} />);

    expect(screen.getByText('Candidate Verification Required')).toBeTruthy();
    expect(screen.getByText('CAPTCHA Challenge')).toBeTruthy();
    expect(screen.getByText(/never attempts to solve or bypass CAPTCHAs/i)).toBeTruthy();
    expect(screen.getByText('Open Application Form').getAttribute('href')).toBe('https://jobs.lever.co/example/apply');
  });

  it('calls completeHandoff and notifies parent on confirm click', async () => {
    vi.mocked(client.completeHandoff).mockResolvedValueOnce({
      sessionId: 'test-session-123',
      status: 'COMPLETED',
      message: 'Resumed',
    });

    render(<HandoffModal session={mockSession} onClose={onClose} onResumed={onResumed} />);

    const confirmBtn = screen.getByRole('button', { name: /confirm & resume application/i });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(client.completeHandoff).toHaveBeenCalledWith('test-session-123');
      expect(onResumed).toHaveBeenCalledTimes(1);
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  it('handles cancellation confirmation and calls cancelHandoff', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.mocked(client.cancelHandoff).mockResolvedValueOnce({
      sessionId: 'test-session-123',
      status: 'CANCELLED',
      message: 'Cancelled',
    });

    render(<HandoffModal session={mockSession} onClose={onClose} onResumed={onResumed} />);

    const cancelBtn = screen.getByRole('button', { name: /cancel application/i });
    fireEvent.click(cancelBtn);

    await waitFor(() => {
      expect(client.cancelHandoff).toHaveBeenCalledWith('test-session-123');
      expect(onResumed).toHaveBeenCalledTimes(1);
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  it('displays error message when completion fails', async () => {
    vi.mocked(client.completeHandoff).mockRejectedValueOnce(new Error('Session has expired'));

    render(<HandoffModal session={mockSession} onClose={onClose} onResumed={onResumed} />);

    const confirmBtn = screen.getByRole('button', { name: /confirm & resume application/i });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(screen.getByText('Session has expired')).toBeTruthy();
      expect(onClose).not.toHaveBeenCalled();
    });
  });

  it('disables confirm button when session is expired', () => {
    const expiredSession: HandoffSession = {
      ...mockSession,
      expiresAt: new Date(Date.now() - 5000).toISOString(),
    };

    render(<HandoffModal session={expiredSession} onClose={onClose} onResumed={onResumed} />);

    const confirmBtn = screen.getByRole('button', { name: /confirm & resume application/i });
    expect(confirmBtn.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('EXPIRED')).toBeTruthy();
  });
});
