import React from 'react';
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';

import { AuthProvider } from '../context/AuthContext';
import { LoginPage } from './LoginPage';
import { SignUpPage } from './SignUpPage';
import { ForgotPasswordPage } from './ForgotPasswordPage';
import { describeSignUpFailure } from './SignUpPage';
import { ApiError } from '../api/client';
import { AuthFailure } from '../firebase/authService';

/**
 * Render-level tests for the authentication screens.
 *
 * Rendering to static markup means effects do not run, so these assert the
 * structure a visitor sees on first paint — including the important property
 * that a build without Firebase configuration explains itself instead of
 * showing a form that cannot work. Behavioural rules (validation, gate
 * decisions) are covered by their own pure tests.
 */
const render = (element: React.ReactElement) =>
  renderToStaticMarkup(
    React.createElement(
      AuthProvider,
      null,
      React.createElement(MemoryRouter, null, element),
    ),
  );

describe('sign in page', () => {
  const html = render(React.createElement(LoginPage));

  it('renders the branded shell', () => {
    expect(html).toContain('Robin');
    expect(html).toContain('Your AI Job Agent');
    expect(html).toContain('Welcome back');
  });

  it('renders the email and password fields', () => {
    expect(html).toContain('Email Address');
    expect(html).toContain('Password');
    expect(html).toContain('type="email"');
    expect(html).toContain('type="password"');
    // React's server renderer emits this attribute camelCased.
    expect(html).toMatch(/autocomplete="email"/i);
    expect(html).toMatch(/autocomplete="current-password"/i);
  });

  it('offers a password visibility toggle', () => {
    expect(html).toContain('aria-label="Show password"');
    expect(html).toContain('Show');
  });

  it('links to account creation and to password reset', () => {
    expect(html).toContain("Don&#x27;t have an account?");
    expect(html).toContain('Create one');
    expect(html).toContain('href="/signup"');
    expect(html).toContain('Forgot your password?');
    expect(html).toContain('href="/forgot-password"');
  });

  it('offers Google sign-in alongside the password form', () => {
    expect(html).toContain('Continue with Google');
  });

  it('starts with empty fields and no prefilled credential', () => {
    expect(html).not.toContain('value="dev@example.local"');
    expect(html).not.toContain('value="DevPassword123!"');
    expect(html).not.toContain('Auto-fill Dev Credentials');
    expect(html).not.toContain('Local Dev Mode');
  });

  it('explains that Firebase is not configured for this build', () => {
    // The test environment supplies no VITE_FIREBASE_* variables, which is
    // exactly the misconfigured-deployment case.
    expect(html).toContain('Firebase Authentication is not configured');
    expect(html).toContain('VITE_FIREBASE_API_KEY');
    expect(html).toContain('VITE_FIREBASE_APP_ID');
  });

  it('never renders a server-side secret name', () => {
    expect(html).not.toContain('FIREBASE_PRIVATE_KEY');
    expect(html).not.toContain('FIREBASE_CLIENT_EMAIL');
  });
});

describe('sign up page', () => {
  const html = render(React.createElement(SignUpPage));

  it('renders every registration field', () => {
    expect(html).toContain('Full Name');
    expect(html).toContain('Email Address');
    expect(html).toContain('Password');
    expect(html).toContain('Confirm Password');
    expect(html).toContain('Invite Code');
  });

  it('places the confirmation field adjacent to the password', () => {
    expect(html.indexOf('Confirm Password')).toBeGreaterThan(html.indexOf('Password'));
  });

  it('offers a visibility toggle on both password fields', () => {
    const toggles = html.match(/aria-label="Show password"/g) ?? [];
    expect(toggles).toHaveLength(2);
  });

  it('uses the right autocomplete hints so password managers behave', () => {
    expect(html).toMatch(/autocomplete="name"/i);
    expect(html.match(/autocomplete="new-password"/gi) ?? []).toHaveLength(2);
  });

  it('links back to sign in', () => {
    expect(html).toContain('Already have an account?');
    expect(html).toContain('href="/login"');
  });

  it('states the password policy before the user fails it', () => {
    expect(html).toContain('Use at least 8 characters, including a letter and a number.');
  });

  it('states where the credential is handled', () => {
    expect(html).toContain('Firebase Authentication');
  });

  it('explains missing configuration rather than showing a dead form', () => {
    expect(html).toContain('Firebase Authentication is not configured');
  });

  it('never exposes development credentials', () => {
    expect(html).not.toContain('dev@example.local');
    expect(html).not.toContain('DevPassword123!');
    expect(html).not.toContain('Auto-fill');
    expect(html).not.toContain('Local Dev Mode');
  });
});

describe('forgot password page', () => {
  const html = render(React.createElement(ForgotPasswordPage));

  it('renders the email form', () => {
    expect(html).toContain('Reset your password');
    expect(html).toContain('Email Address');
    expect(html).toContain('Send reset link');
    expect(html).toContain('type="email"');
  });

  it('links back to sign in', () => {
    expect(html).toContain('Back to sign in');
    expect(html).toContain('href="/login"');
  });

  it('does not generate or display a reset token', () => {
    // The whole reset flow lives inside Firebase; nothing token-shaped may
    // appear in this page's markup.
    expect(html).not.toContain('resetToken');
    expect(html).not.toContain('reset_token');
    expect(html).not.toContain('token=');
  });

  it('explains missing configuration', () => {
    expect(html).toContain('Firebase Authentication is not configured');
  });
});

describe('sign-up failure messaging', () => {
  it('explains an already-registered email in terms of what to do next', () => {
    // Duplicate-email handling: Firebase reports this before the backend is
    // ever involved.
    const message = describeSignUpFailure(
      new AuthFailure('auth/email-already-in-use', 'An account already exists for that email address. Try signing in instead.'),
    );
    expect(message).toContain('already exists');
    expect(message).toContain('signing in');
  });

  it('turns a refused invite code into an actionable message', () => {
    const message = describeSignUpFailure(
      new ApiError(403, 'A registration invite code is required to create an account.'),
    );
    expect(message).toContain('invite code');
  });

  it('sends an unverified email to the verification step, not a dead end', () => {
    // The backend refuses to provision from an unverified token; the message
    // must point back to the verification email, never echo the raw refusal.
    const message = describeSignUpFailure(
      new ApiError(403, 'This email address has not been verified yet. Follow the verification link Firebase emailed you, then sign in again.'),
    );
    expect(message).toContain('not verified');
    expect(message).toContain('verification link');
  });

  it('reports a server without Firebase credentials plainly', () => {
    const message = describeSignUpFailure(new ApiError(503, 'Firebase Authentication is not configured'));
    expect(message).toContain('no Firebase credentials');
  });

  it('never echoes an unknown server payload verbatim', () => {
    const message = describeSignUpFailure(new ApiError(500, 'org.postgresql.util.PSQLException: relation users'));
    expect(message).not.toContain('PSQLException');
    expect(message).toContain('try again');
  });

  it('falls back to a generic message for a non-Error throw', () => {
    expect(describeSignUpFailure('boom')).toContain('Could not create your account');
  });
});
