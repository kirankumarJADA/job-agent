import React, { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import { AuthLayout } from '../components/AuthLayout';
import { AuthNotice } from '../components/AuthNotice';
import { FirebaseConfigNotice } from '../components/FirebaseConfigNotice';
import { FormField } from '../components/FormField';
import { useAuth } from '../context/AuthContext';
import { AuthFailure } from '../firebase/authService';
import { validateSignIn, isValid, type FieldErrors, type SignInField } from '../auth/validation';

/**
 * Sign in.
 *
 * Validation runs on submit rather than on every keystroke, so the form does
 * not shout at someone who is still typing; once a field has been shown as
 * invalid it re-validates as they type, so the message clears as soon as it is
 * fixed.
 */
export const LoginPage: React.FC = () => {
  const { login, firebaseConfigured, missingFirebaseKeys } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<FieldErrors<SignInField>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  /** Where to land after signing in — set by ProtectedRoute when it redirected. */
  const redirectTo = (location.state as { from?: string } | null)?.from ?? '/';

  const updateField = (field: SignInField, value: string, setter: (v: string) => void) => {
    setter(value);
    setFormError(null);
    if (errors[field]) {
      setErrors(validateSignIn({ email, password, [field]: value }));
    }
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setFormError(null);

    const validation = validateSignIn({ email, password });
    setErrors(validation);
    if (!isValid(validation)) {
      return;
    }

    setSubmitting(true);
    try {
      await login(email, password);
      navigate(redirectTo, { replace: true });
    } catch (error) {
      // Firebase reports bad email and bad password identically, so there is no
      // useful per-field message to show here.
      setFormError(
        error instanceof AuthFailure || error instanceof Error
          ? error.message
          : 'Sign-in failed. Please try again.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      eyebrow="Sign in"
      title="Welcome back"
      subtitle="Sign in to pick up your job search where you left off."
      footer={
        <>
          Don&apos;t have an account?{' '}
          <Link to="/signup" className="font-semibold text-indigo-400 hover:text-indigo-300">
            Create one
          </Link>
        </>
      }
    >
      {!firebaseConfigured && <FirebaseConfigNotice missingKeys={missingFirebaseKeys} />}

      {formError && <AuthNotice tone="error">{formError}</AuthNotice>}

      {/*
        Kept enabled even without Firebase configuration, so the layout and the
        validation messages are still verifiable; submitting surfaces the same
        configuration notice rather than silently doing nothing.
      */}
      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <FormField
          label="Email Address"
          type="email"
          value={email}
          onChange={(value) => updateField('email', value, setEmail)}
          placeholder="you@company.com"
          autoComplete="email"
          autoFocus
          disabled={submitting}
          error={errors.email}
        />

        <div>
          <FormField
            label="Password"
            type="password"
            revealable
            value={password}
            onChange={(value) => updateField('password', value, setPassword)}
            placeholder="••••••••"
            autoComplete="current-password"
            disabled={submitting}
            error={errors.password}
          />
          <div className="mt-2 text-right">
            <Link
              to="/forgot-password"
              className="text-xs font-medium text-indigo-400 hover:text-indigo-300"
            >
              Forgot your password?
            </Link>
          </div>
        </div>

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 font-medium text-white shadow-lg shadow-indigo-600/20 transition-all hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:ring-offset-2 focus:ring-offset-slate-900 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submitting ? 'Signing in…' : 'Sign In'}
        </button>
      </form>
    </AuthLayout>
  );
};
