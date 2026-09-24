import React, { useState } from 'react';
import { Link } from 'react-router-dom';

import { AuthLayout } from '../components/AuthLayout';
import { AuthNotice } from '../components/AuthNotice';
import { FirebaseConfigNotice } from '../components/FirebaseConfigNotice';
import { FormField } from '../components/FormField';
import { useAuth } from '../context/AuthContext';
import { AuthFailure } from '../firebase/authService';
import {
  isValid,
  validateForgotPassword,
  type FieldErrors,
  type ForgotPasswordField,
} from '../auth/validation';

/**
 * Forgot password.
 *
 * Intentionally thin: Firebase sends its own reset email and owns the reset
 * token end to end. This application generates no token, stores no token and
 * accepts no token — there is nothing here that could leak one.
 *
 * The success message is shown regardless of whether the address exists, which
 * matches Firebase's own behaviour and avoids turning this page into an account
 * enumeration oracle.
 */
export const ForgotPasswordPage: React.FC = () => {
  const { requestPasswordReset, firebaseConfigured, missingFirebaseKeys } = useAuth();

  const [email, setEmail] = useState('');
  const [errors, setErrors] = useState<FieldErrors<ForgotPasswordField>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setFormError(null);

    const validation = validateForgotPassword({ email });
    setErrors(validation);
    if (!isValid(validation)) {
      return;
    }

    setSubmitting(true);
    try {
      await requestPasswordReset(email);
      setSent(true);
    } catch (error) {
      setFormError(
        error instanceof AuthFailure || error instanceof Error
          ? error.message
          : 'Could not send the reset email. Please try again.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      eyebrow="Password reset"
      title="Reset your password"
      subtitle="Enter your email address and we'll send you a link to choose a new password."
      footer={
        <>
          Remembered it?{' '}
          <Link to="/login" className="font-semibold text-indigo-400 hover:text-indigo-300">
            Back to sign in
          </Link>
        </>
      }
    >
      {!firebaseConfigured && <FirebaseConfigNotice missingKeys={missingFirebaseKeys} />}

      {sent ? (
        <AuthNotice tone="success">
          <p className="font-semibold text-emerald-200">Check your inbox</p>
          <p className="mt-1.5">
            If an account exists for <span className="font-medium">{email.trim()}</span>, a password
            reset link is on its way. The link expires shortly, so use it soon.
          </p>
          <p className="mt-2 text-xs text-emerald-200/80">
            Nothing arrived? Check your spam folder, then try again.
          </p>
        </AuthNotice>
      ) : (
        <>
          {formError && <AuthNotice tone="error">{formError}</AuthNotice>}

          <form onSubmit={handleSubmit} className="space-y-4" noValidate>
            <FormField
              label="Email Address"
              type="email"
              value={email}
              onChange={(value) => {
                setEmail(value);
                setFormError(null);
              }}
              placeholder="you@company.com"
              autoComplete="email"
              autoFocus
              disabled={submitting}
              error={errors.email}
            />

            <button
              type="submit"
              disabled={submitting}
              className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 font-medium text-white shadow-lg shadow-indigo-600/20 transition-all hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:ring-offset-2 focus:ring-offset-slate-900 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {submitting ? 'Sending reset link…' : 'Send reset link'}
            </button>
          </form>
        </>
      )}
    </AuthLayout>
  );
};
