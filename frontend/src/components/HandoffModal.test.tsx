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

  it('renders modal with external completion title, block reason, and 5-step instructions', () => {
    render(<HandoffModal session={mockSession} onClose={onClose} onResumed={onResumed} />);

    expect(screen.getByText('External Application & Verification Required')).toBeTruthy();
    expect(screen.getByText('CAPTCHA Challenge')).toBeTruthy();
    expect(screen.getByText(/never attempts to solve or bypass CAPTCHAs/i)).toBeTruthy();
    expect(screen.getByText('Open Employer Application Form').getAttribute('href')).toBe('https://jobs.lever.co/example/apply');
    expect(screen.getByText(/Review and complete all required application fields on the employer site/i)).toBeTruthy();
    expect(screen.getByText(/Submit the application directly on the employer website/i)).toBeTruthy();
  });

  it('requires explicit candidate attestation before external submission can be confirmed', async () => {
    render(<HandoffModal session={mockSession} onClose={onClose} onResumed={onResumed} />);

    const confirmBtn = screen.getByRole('button', { name: /confirm external submission/i });
    // Proves that merely resolving the challenge without attestation is NOT enough to confirm submission
    expect(confirmBtn.hasAttribute('disabled')).toBe(true);

    const attestationCheckbox = screen.getByRole('checkbox', { name: /attest external submission/i });
    fireEvent.click(attestationCheckbox);

    // Button is now enabled
    expect(confirmBtn.hasAttribute('disabled')).toBe(false);
  });

  it('calls completeHandoff with external submission attestation on confirm click', async () => {
    vi.mocked(client.completeHandoff).mockResolvedValueOnce({
      sessionId: 'test-session-123',
      status: 'COMPLETED',
      message: 'Attestation recorded',
    });

    render(<HandoffModal session={mockSession} onClose={onClose} onResumed={onResumed} />);

    const attestationCheckbox = screen.getByRole('checkbox', { name: /attest external submission/i });
    fireEvent.click(attestationCheckbox);

    const confirmBtn = screen.getByRole('button', { name: /confirm external submission/i });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(client.completeHandoff).toHaveBeenCalledWith('test-session-123', { submittedExternally: true });
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

    const attestationCheckbox = screen.getByRole('checkbox', { name: /attest external submission/i });
    fireEvent.click(attestationCheckbox);

    const confirmBtn = screen.getByRole('button', { name: /confirm external submission/i });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(screen.getByText('Session has expired')).toBeTruthy();
      expect(onClose).not.toHaveBeenCalled();
    });
  });

  it('disables confirm button when session is expired even if attestation checked', () => {
    const expiredSession: HandoffSession = {
      ...mockSession,
      expiresAt: new Date(Date.now() - 5000).toISOString(),
    };

    render(<HandoffModal session={expiredSession} onClose={onClose} onResumed={onResumed} />);

    const confirmBtn = screen.getByRole('button', { name: /confirm external submission/i });
    expect(confirmBtn.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('EXPIRED')).toBeTruthy();
  });

  it('disables confirm button and checkbox when session status is not PENDING', () => {
    const cancelledSession: HandoffSession = {
      ...mockSession,
      status: 'CANCELLED',
    };

    render(<HandoffModal session={cancelledSession} onClose={onClose} onResumed={onResumed} />);

    const attestationCheckbox = screen.getByRole('checkbox', { name: /attest external submission/i });
    expect(attestationCheckbox.hasAttribute('disabled')).toBe(true);

    const confirmBtn = screen.getByRole('button', { name: /confirm external submission/i });
    expect(confirmBtn.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('CANCELLED')).toBeTruthy();
  });
});
