import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { AuthLayout } from '../components/AuthLayout';
import { AuthNotice } from '../components/AuthNotice';
import { FormField } from '../components/FormField';
import { useAuth } from '../context/AuthContext';
import { ApiError } from '../api/client';
import { AuthFailure } from '../firebase/authService';

/** Seconds a just-sent verification email blocks the resend button. */
const RESEND_COOLDOWN_SECONDS = 60;

/**
 * Email verification — the second stage of email/password sign-up.
 *
 * Firebase created the account and sent its own verification email; no
 * application session exists yet and none may: the backend refuses to
 * provision from an unverified token, so the gates would bounce this visitor
 * anyway. This screen is where they wait, resend, and confirm — and the
 * "Check again" action is the only path into the application.
 *
 * Firebase hosts the link itself (including its expiry handling); this page
 * never sees or validates a token, it only reloads the Firebase user and asks
 * whether the address is verified now.
 */
export const VerifyEmailPage: React.FC = () => {
  const { pendingVerification, resendVerificationEmail, checkEmailVerification, clearPendingVerification } =
    useAuth();
  const navigate = useNavigate();

  const [resending, setResending] = useState(false);
  const [resendNotice, setResendNotice] = useState<{ tone: 'success' | 'error'; text: string } | null>(null);
  const [cooldownSeconds, setCooldownSeconds] = useState(0);

  const [checking, setChecking] = useState(false);
  const [checkNotice, setCheckNotice] = useState<{ tone: 'error'; text: string } | null>(null);
  const [inviteRequired, setInviteRequired] = useState(false);
  const [inviteCode, setInviteCode] = useState('');

  /** Latest resend request, so a slow response cannot race the countdown. */
  const resendSequence = useRef(0);

  const email = pendingVerification?.email ?? '';

  // The cooldown ticks only while it is running, and stops when it hits zero.
  const cooling = cooldownSeconds > 0;
  useEffect(() => {
    if (!cooling) {
      return () => undefined;
    }
    const timer = window.setInterval(() => {
      setCooldownSeconds((seconds) => Math.max(0, seconds - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [cooling]);

  const handleResend = useCallback(async () => {
    const sequence = ++resendSequence.current;
    setResending(true);
    setResendNotice(null);
    try {
      await resendVerificationEmail();
      if (resendSequence.current === sequence) {
        setResendNotice({
          tone: 'success',
          text: 'Verification email sent. If it does not arrive within a few minutes, check your spam folder.',
        });
        setCooldownSeconds(RESEND_COOLDOWN_SECONDS);
      }
    } catch (error) {
      if (resendSequence.current === sequence) {
        setResendNotice({ tone: 'error', text: describeVerificationFailure(error) });
      }
    } finally {
      if (resendSequence.current === sequence) {
        setResending(false);
      }
    }
  }, [resendVerificationEmail]);

  const handleCheckAgain = useCallback(
    async (inviteOverride?: string) => {
      setChecking(true);
      setCheckNotice(null);
      try {
        const result = await checkEmailVerification(inviteOverride ? { inviteCode: inviteOverride } : undefined);
        if (result === 'not-verified') {
          setCheckNotice({
            tone: 'error',
            text: 'Not verified yet. Follow the link in the email we sent, then try again. Links expire — use Resend below if yours has.',
          });
        }
        // 'verified' established the session: PublicOnly routing sends the
        // user into the application from here.
      } catch (error) {
        if (error instanceof ApiError && error.status === 403 && error.message.includes('invite')) {
          // Registration is gated and the code from the sign-up form did not
          // reach the exchange (lost with the tab) or was wrong. Collect a
          // fresh one and retry on the next click.
          setInviteRequired(true);
          setCheckNotice({ tone: 'error', text: 'That invite code was not accepted. Enter a valid one and check again.' });
          return;
        }
        setCheckNotice({ tone: 'error', text: describeVerificationFailure(error) });
      } finally {
        setChecking(false);
      }
    },
    [checkEmailVerification],
  );

  const handleBackToSignIn = useCallback(async () => {
    // Abandon the pending account: back out of Firebase so the browser is
    // genuinely signed out, and forget the deferred exchange.
    await clearPendingVerification();
    navigate('/login', { replace: true });
  }, [clearPendingVerification, navigate]);

  return (
    <AuthLayout
      eyebrow="Almost there"
      title="Verify your email"
      subtitle="One quick check and Robin can start working for you."
      footer={
        <>
          Wrong address or changed your mind?{' '}
          <button
            type="button"
            onClick={handleBackToSignIn}
            className="font-semibold text-indigo-400 hover:text-indigo-300"
          >
            Back to Sign In
          </button>
        </>
      }
    >
      <div className="rounded-xl border border-indigo-500/30 bg-indigo-500/10 p-4">
        <p className="text-sm leading-relaxed text-slate-200">
          We&apos;ve sent a verification link to{' '}
          <span className="font-semibold text-white">{email || 'your email address'}</span>.
        </p>
        <p className="mt-2 text-xs leading-relaxed text-slate-400">
          Follow the link in that email, then come back here and press{' '}
          <span className="font-medium text-slate-300">Check again</span>. The link expires after a while — use{' '}
          <span className="font-medium text-slate-300">Resend</span> to get a fresh one.
        </p>
      </div>

      {resendNotice && <AuthNotice tone={resendNotice.tone}>{resendNotice.text}</AuthNotice>}
      {checkNotice && <AuthNotice tone={checkNotice.tone}>{checkNotice.text}</AuthNotice>}

      <div className="space-y-3">
        <button
          type="button"
          onClick={() => handleCheckAgain(inviteRequired ? inviteCode.trim() : undefined)}
          disabled={checking}
          className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 font-medium text-white shadow-lg shadow-indigo-600/20 transition-all hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:ring-offset-2 focus:ring-offset-slate-900 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {checking ? 'Checking…' : "I've verified my email — Check again"}
        </button>

        {inviteRequired && (
          <FormField
            label="Invite Code"
            value={inviteCode}
            onChange={(value) => setInviteCode(value)}
            placeholder="Provided by whoever invited you"
            autoComplete="off"
            disabled={checking}
            hint="Your sign-up invite code was not applied. Enter it here to finish creating your account."
          />
        )}

        <button
          type="button"
          onClick={handleResend}
          disabled={resending || cooldownSeconds > 0}
          className="w-full rounded-lg border border-slate-700 px-4 py-2.5 font-medium text-slate-200 transition-all hover:border-slate-600 hover:bg-slate-800/60 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:ring-offset-2 focus:ring-offset-slate-900 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {resending
            ? 'Sending…'
            : cooldownSeconds > 0
              ? `Resend available in ${cooldownSeconds}s`
              : 'Resend verification email'}
        </button>
      </div>

      <p className="text-center text-xs leading-relaxed text-slate-500">
        Keep this tab open if you can — but signing in again later works too, and picks up right here.
      </p>
    </AuthLayout>
  );
};

/**
 * Turns a verification-flow failure into something the person can act on.
 * Firebase errors arrive pre-mapped (AuthFailure); backend errors are mapped
 * by status so no raw exception text ever reaches the screen.
 */
export function describeVerificationFailure(error: unknown): string {
  if (error instanceof AuthFailure) {
    if (error.code === 'auth/no-current-user') {
      return error.message;
    }
    return error.message;
  }
  if (error instanceof ApiError) {
    if (error.status === 401) {
      return 'Your sign-in could not be verified. Press Check again to retry, or sign in once more.';
    }
    if (error.status === 403) {
      return error.message.includes('invite')
        ? 'That invite code is not valid. Check it and try again.'
        : error.message;
    }
    if (error.status === 503) {
      return 'This deployment has no Firebase credentials configured yet, so accounts cannot be created. Please contact the operator.';
    }
    if (error.status >= 500) {
      return 'The server could not finish setting up your account. Please try again in a moment.';
    }
    return 'Something went wrong. Please try again in a moment.';
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'Something went wrong. Please try again in a moment.';
}
