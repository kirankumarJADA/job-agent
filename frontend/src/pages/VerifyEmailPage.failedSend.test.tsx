import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import type { AuthContextType, PendingVerification } from '../context/AuthContext';
import { shouldShowVerificationSentCopy } from './VerifyEmailPage';

// The real AuthProvider resolves its pending state from live Firebase, which
// the static renderer never exercises. Mocking the context lets these tests
// pin exactly what the screen says when the automatic verification-email send
// failed at sign-up versus when it succeeded.
const state = vi.hoisted(() => ({ current: null as AuthContextType | null }));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => {
    if (!state.current) {
      throw new Error('stub auth state not initialised');
    }
    return state.current;
  },
}));

const { VerifyEmailPage } = await import('./VerifyEmailPage');

const stubAuth = (pending: PendingVerification | null): AuthContextType =>
  ({
    user: null,
    loading: false,
    firebaseConfigured: true,
    missingFirebaseKeys: [],
    awaitingVerification: pending !== null,
    pendingVerification: pending,
    login: vi.fn(),
    signUp: vi.fn(),
    signInWithGoogle: vi.fn(),
    resendVerificationEmail: vi.fn(),
    checkEmailVerification: vi.fn(),
    clearPendingVerification: vi.fn(),
    logout: vi.fn(),
    requestPasswordReset: vi.fn(),
    refresh: vi.fn(),
  }) as unknown as AuthContextType;

const render = (element: React.ReactElement) =>
  renderToStaticMarkup(React.createElement(MemoryRouter, null, element));

describe('verify email page — send-failure honesty', () => {
  it('says the automatic send failed instead of claiming an email is on its way', () => {
    state.current = stubAuth({
      email: 'person@example.com',
      signup: true,
      verificationEmailFailed: true,
    });

    const html = render(React.createElement(VerifyEmailPage));

    expect(html).toContain('could not send the verification email');
    expect(html).not.toContain('sent a verification link to');
    // The recovery affordances stay available.
    expect(html).toContain('Resend verification email');
    expect(html).toContain('Check again');
  });

  it('keeps the sent-link copy when the automatic send succeeded', () => {
    state.current = stubAuth({ email: 'person@example.com', signup: true });

    const html = render(React.createElement(VerifyEmailPage));

    expect(html).toContain('sent a verification link to');
    expect(html).toContain('person@example.com');
    expect(html).not.toContain('could not send');
  });

  it('does not restore a sent claim while the persisted signup send failure remains', () => {
    state.current = stubAuth({
      email: 'person@example.com',
      signup: true,
      verificationEmailFailed: true,
    });

    const html = render(React.createElement(VerifyEmailPage));

    expect(html).toContain('could not send the verification email');
    expect(html).not.toContain('sent a verification link to');
    expect(shouldShowVerificationSentCopy(state.current.pendingVerification)).toBe(false);
  });

  it('shows the sent claim only after a successful resend clears the failure flag', () => {
    const pendingAfterSuccessfulResend = stubAuth({
      email: 'person@example.com',
      signup: true,
    }).pendingVerification;

    expect(shouldShowVerificationSentCopy(pendingAfterSuccessfulResend)).toBe(true);
  });

  it('never shows a sent claim without a pending Firebase verification', () => {
    expect(shouldShowVerificationSentCopy(null)).toBe(false);
  });
});
