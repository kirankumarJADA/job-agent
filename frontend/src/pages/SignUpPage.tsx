import React, { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { AuthLayout } from '../components/AuthLayout';
import { AuthNotice } from '../components/AuthNotice';
import { FirebaseConfigNotice } from '../components/FirebaseConfigNotice';
import { FormField } from '../components/FormField';
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
 * The flow is deliberately two-stage and the second stage is not optional:
 * Firebase creates the credential, then the backend creates the application
 * account after verifying the ID token and the registration invite code. If the
 * backend refuses, AuthContext signs the Firebase user back out, so a refused
 * sign-up leaves nothing behind.
 *
 * The invite-code field's requiredness comes from the server
 * (`/auth/registration-policy`) rather than being guessed, so the form does not
 * demand a code the server would ignore, nor omit one the server requires.
 */
export const SignUpPage: React.FC = () => {
  const { signUp, firebaseConfigured, missingFirebaseKeys } = useAuth();
  const navigate = useNavigate();

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [inviteCode, setInviteCode] = useState('');

  const [errors, setErrors] = useState<FieldErrors<SignUpField>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  /** null while the policy is still unknown — treated as "do not insist". */
  const [inviteCodeRequired, setInviteCodeRequired] = useState<boolean | null>(null);
  const [registrationAvailable, setRegistrationAvailable] = useState(true);

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
        // Policy is an enhancement, not a prerequisite. If it cannot be read
        // the form still submits and the backend remains the authority.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const currentInput = { fullName, email, password, confirmPassword, inviteCode };

  /** Re-validate only fields already showing an error, so typing is not nagged. */
  const revalidate = (field: SignUpField, value: string) => {
    if (!errors[field]) {
      return;
    }
    setErrors(validateSignUp({ ...currentInput, [field]: value }, inviteCodeRequired === true));
  };

  const setField = (field: SignUpField, value: string, setter: (v: string) => void) => {
    setter(value);
    setFormError(null);
    revalidate(field, value);
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setFormError(null);

    const validation = validateSignUp(currentInput, inviteCodeRequired === true);
    setErrors(validation);
    if (!isValid(validation)) {
      return;
    }

    setSubmitting(true);
    try {
      await signUp({ fullName, email, password, inviteCode });
      // Straight into the application as an authenticated user.
      navigate('/', { replace: true });
    } catch (error) {
      setFormError(describeSignUpFailure(error));
    } finally {
      setSubmitting(false);
    }
  };

  const strengthIssues = password === '' ? [] : passwordProblems(password);

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

      {formError && <AuthNotice tone="error">{formError}</AuthNotice>}

      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <FormField
          label="Full Name"
          value={fullName}
          onChange={(value) => setField('fullName', value, setFullName)}
          placeholder="Alex Morgan"
          autoComplete="name"
          autoFocus
          disabled={submitting}
          error={errors.fullName}
        />

        <FormField
          label="Email Address"
          type="email"
          value={email}
          onChange={(value) => setField('email', value, setEmail)}
          placeholder="you@company.com"
          autoComplete="email"
          disabled={submitting}
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
            disabled={submitting}
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
          disabled={submitting}
          error={errors.confirmPassword}
        />

        <FormField
          label={inviteCodeRequired === true ? 'Invite Code' : 'Invite Code (optional)'}
          value={inviteCode}
          onChange={(value) => setField('inviteCode', value, setInviteCode)}
          placeholder="Provided by whoever invited you"
          autoComplete="off"
          disabled={submitting}
          error={errors.inviteCode}
          hint={
            inviteCodeRequired === true
              ? 'Registration is limited to invited users.'
              : undefined
          }
        />

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 font-medium text-white shadow-lg shadow-indigo-600/20 transition-all hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:ring-offset-2 focus:ring-offset-slate-900 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submitting ? 'Creating your account…' : 'Create Account'}
        </button>

        <p className="text-center text-xs leading-relaxed text-slate-500">
          Your password is handled by Firebase Authentication. Robin never stores it.
        </p>
      </form>
    </AuthLayout>
  );
};

/**
 * Turns a sign-up failure into something the person can act on.
 *
 * The order matters: Firebase errors first (they are the specific ones — a
 * duplicate email, a weak password), then application errors by status.
 */
export function describeSignUpFailure(error: unknown): string {
  if (error instanceof AuthFailure) {
    return error.message;
  }
  if (error instanceof ApiError) {
    if (error.status === 403) {
      // The registration gate refused: a missing or wrong invite code, or
      // registration closed on this deployment.
      return error.message.includes('invite')
        ? 'That invite code is not valid. Check it and try again.'
        : error.message;
    }
    if (error.status === 503) {
      return 'This deployment has no Firebase credentials configured yet, so accounts cannot be created. Please contact the operator.';
    }
    if (error.status >= 500) {
      return 'The server could not create your account. Please try again in a moment.';
    }
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'Could not create your account. Please try again.';
}
