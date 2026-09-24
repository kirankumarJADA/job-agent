import React, { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { AuthLayout } from '../components/AuthLayout';
import { AuthNotice } from '../components/AuthNotice';
import { FirebaseConfigNotice } from '../components/FirebaseConfigNotice';
import { FormField } from '../components/FormField';
import { GoogleButton } from '../components/GoogleButton';
import { useAuth } from '../context/AuthContext';
import { ApiError, fetchRegistrationPolicy } from '../api/client';
import { AuthFailure } from '../firebase/authService';
import {
  PASSWORD_MIN_LENGTH,
  isValid,
  passwordProblems,
  validateSignUp,
  type FieldErrors,
  type SignUpField,
} from '../auth/validation';

/**
 * Create account.
 *
 * The flow is deliberately three-stage and none of the stages is optional:
 * Firebase creates the credential and sends its verification email, the
 * visitor follows that link, and only then does this application ask the
 * backend for a session — the backend verifies the token cryptographically and
 * enforces the registration invite code there. An unverified account can
 * neither reach the application nor be refused into it; the verification
 * screen owns that waiting period.
 *
 * The invite-code field's requiredness comes from the server
 * (`/auth/registration-policy`) rather than being guessed. When the policy
 * cannot be read, the field is treated as required — the safe direction: the
 * backend refuses a missing code, and an unnecessary one is ignored.
 */
export const SignUpPage: React.FC = () => {
  const { signUp, signInWithGoogle, firebaseConfigured, missingFirebaseKeys } = useAuth();
  const navigate = useNavigate();

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [inviteCode, setInviteCode] = useState('');

  const [errors, setErrors] = useState<FieldErrors<SignUpField>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [googleSubmitting, setGoogleSubmitting] = useState(false);

  /** null while the policy is still unknown — treated as "do not insist". */
  const [inviteCodeRequired, setInviteCodeRequired] = useState<boolean | null>(null);
  const [registrationAvailable, setRegistrationAvailable] = useState(true);
  const [policyUnavailable, setPolicyUnavailable] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const policy = await fetchRegistrationPolicy();
        if (!cancelled) {
          setInviteCodeRequired(policy.inviteCodeRequired);
          setRegistrationAvailable(policy.registrationAvailable);
        }
      } catch {
        // The backend is the authority either way: label the field as required
        // (fail closed) and say so honestly rather than guessing.
        if (!cancelled) {
          setPolicyUnavailable(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const currentInput = { fullName, email, password, confirmPassword, inviteCode };

  // Fail closed: the field is labelled and validated as required whenever the
  // server has not explicitly said registration is open.
  const showInviteCodeRequired = inviteCodeRequired !== false;

  /** Re-validate only fields already showing an error, so typing is not nagged. */
  const revalidate = (field: SignUpField, value: string) => {
    if (!errors[field]) {
      return;
    }
    setErrors(validateSignUp({ ...currentInput, [field]: value }, showInviteCodeRequired));
  };

  const setField = (field: SignUpField, value: string, setter: (v: string) => void) => {
    setter(value);
    setFormError(null);
    revalidate(field, value);
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setFormError(null);

    const validation = validateSignUp(currentInput, showInviteCodeRequired);
    setErrors(validation);
    if (!isValid(validation)) {
      return;
    }

    setSubmitting(true);
    try {
      await signUp({ fullName, email, password, inviteCode });
      // The Firebase account exists and its verification email is on its way;
      // the route guards take the visitor to the verification screen from here.
      navigate('/', { replace: true });
    } catch (error) {
      setFormError(describeSignUpFailure(error));
    } finally {
      setSubmitting(false);
    }
  };

  const handleGoogle = async () => {
    setFormError(null);
    setGoogleSubmitting(true);
    try {
      await signInWithGoogle({ inviteCode: inviteCode || undefined });
      navigate('/', { replace: true });
    } catch (error) {
      setFormError(describeSignUpFailure(error));
    } finally {
      setGoogleSubmitting(false);
    }
  };

  const strengthIssues = password === '' ? [] : passwordProblems(password);
  const busy = submitting || googleSubmitting;

  return (
    <AuthLayout
      eyebrow="Create account"
      title="Join Robin"
      subtitle="Set up your account and let your AI job agent start working for you."
      footer={
        <>
          Already have an account?{' '}
          <Link to="/login" className="font-semibold text-indigo-400 hover:text-indigo-300">
            Sign in
          </Link>
        </>
      }
    >
      {!firebaseConfigured && <FirebaseConfigNotice missingKeys={missingFirebaseKeys} />}

      {!registrationAvailable && (
        <AuthNotice tone="error">
          Registration is not available on this deployment. Existing accounts can still sign in.
        </AuthNotice>
      )}

      {policyUnavailable && registrationAvailable && (
        <AuthNotice tone="info">
          The registration policy could not be loaded, so an invite code may be required. You can
          add it before submitting.
        </AuthNotice>
      )}

      {formError && <AuthNotice tone="error">{formError}</AuthNotice>}

      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <FormField
          label="Full Name"
          value={fullName}
          onChange={(value) => setField('fullName', value, setFullName)}
          placeholder="Alex Morgan"
          autoComplete="name"
          autoFocus
          disabled={busy}
          error={errors.fullName}
        />

        <FormField
          label="Email Address"
          type="email"
          value={email}
          onChange={(value) => setField('email', value, setEmail)}
          placeholder="you@company.com"
          autoComplete="email"
          disabled={busy}
          error={errors.email}
        />

        <div>
          <FormField
            label="Password"
            type="password"
            revealable
            value={password}
            onChange={(value) => setField('password', value, setPassword)}
            placeholder={`At least ${PASSWORD_MIN_LENGTH} characters`}
            autoComplete="new-password"
            disabled={busy}
            error={errors.password}
            hint={`Use at least ${PASSWORD_MIN_LENGTH} characters, including a letter and a number.`}
          />
          {strengthIssues.length > 0 && !errors.password && (
            <ul className="mt-2 space-y-0.5">
              {strengthIssues.map((issue) => (
                <li key={issue} className="text-xs text-slate-500">
                  • {issue}
                </li>
              ))}
            </ul>
          )}
        </div>

        <FormField
          label="Confirm Password"
          type="password"
          revealable
          value={confirmPassword}
          onChange={(value) => setField('confirmPassword', value, setConfirmPassword)}
          placeholder="Re-enter your password"
          autoComplete="new-password"
          disabled={busy}
          error={errors.confirmPassword}
        />

        <FormField
          label={showInviteCodeRequired ? 'Invite Code' : 'Invite Code (optional)'}
          value={inviteCode}
          onChange={(value) => setField('inviteCode', value, setInviteCode)}
          placeholder="Provided by whoever invited you"
          autoComplete="off"
          disabled={busy}
          error={errors.inviteCode}
          hint={
            inviteCodeRequired === true
              ? 'Registration is limited to invited users.'
              : undefined
          }
        />

        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 font-medium text-white shadow-lg shadow-indigo-600/20 transition-all hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:ring-offset-2 focus:ring-offset-slate-900 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submitting ? 'Creating your account…' : 'Create Account'}
        </button>

        <div className="flex items-center gap-3" aria-hidden="true">
          <div className="h-px flex-1 bg-slate-700/70" />
          <span className="text-xs font-medium uppercase tracking-wider text-slate-500">or</span>
          <div className="h-px flex-1 bg-slate-700/70" />
        </div>

        <GoogleButton onClick={handleGoogle} disabled={busy} label="Sign up with Google" />

        <p className="text-center text-xs leading-relaxed text-slate-500">
          Your password is handled by Firebase Authentication. Robin never stores it. We&apos;ll
          email you a verification link to confirm your address before your account is created.
        </p>
      </form>
    </AuthLayout>
  );
};

/**
 * Turns a sign-up failure into something the person can act on.
 *
 * The order matters: Firebase errors first (they are the specific ones — a
 * duplicate email, a weak password), then application errors by status. Nothing
 * here echoes a raw exception: every branch ends in deliberate copy.
 */
export function describeSignUpFailure(error: unknown): string {
  if (error instanceof AuthFailure) {
    return error.message;
  }
  if (error instanceof ApiError) {
    if (error.status === 403) {
      // The registration gate refused: a missing or wrong invite code — or an
      // address that was never verified, which the verification screen handles.
      if (error.message.includes('not been verified')) {
        return 'Your email is not verified yet. Follow the verification link we emailed you, then continue.';
      }
      return error.message.includes('invite')
        ? 'That invite code is not valid. Check it and try again.'
        : error.message;
    }
    if (error.status === 503) {
      return 'This deployment has no Firebase credentials configured yet, so accounts cannot be created. Please contact the operator.';
    }
    if (error.status === 401) {
      return 'Your sign-in could not be verified. Please try again in a moment.';
    }
    if (error.status >= 500) {
      return 'The server could not create your account. Please try again in a moment.';
    }
    return 'Your account could not be created yet. Please check your details and try again.';
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'Could not create your account. Please try again.';
}
