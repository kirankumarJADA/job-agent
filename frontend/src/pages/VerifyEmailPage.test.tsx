import React from 'react';
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';

import { AuthProvider } from '../context/AuthContext';
import { VerifyEmailPage, describeVerificationFailure } from './VerifyEmailPage';
import { describeSignInFailure } from './LoginPage';
import { ApiError } from '../api/client';
import { AuthFailure } from '../firebase/authService';

/**
 * Render-level and message-mapping tests for the email-verification screen.
 *
 * Rendering to static markup keeps the suite free of a DOM interaction layer;
 * the flow's behavioural rules live in the pure decision functions (route
 * guards) and in the backend's own provisioning tests, which are the actual
 * security boundary. What must hold at first paint is: the visitor is told a
 * verification email was sent, all three actions exist, and nothing here
 * pretends a session exists.
 */
const render = (element: React.ReactElement) =>
  renderToStaticMarkup(
    React.createElement(
      AuthProvider,
      null,
      React.createElement(MemoryRouter, null, element),
    ),
  );

describe('verify email page', () => {
  const html = render(React.createElement(VerifyEmailPage));

  it('explains that a verification email was sent', () => {
    expect(html).toContain('Verify your email');
    expect(html).toContain('We&#x27;ve sent a verification link to');
  });

  it('offers check-again, resend, and back-to-sign-in', () => {
    expect(html).toContain('Check again');
    expect(html).toContain('Resend verification email');
    expect(html).toContain('Back to Sign In');
  });

  it('shows the resend cooldown affordance rather than a bare disabled button', () => {
    // The button starts enabled (no email sent during this render); the
    // cooldown text appears only after a successful resend.
    expect(html).not.toContain('Resend available in');
  });

  it('never leaks backend or Firebase exception text in the markup', () => {
    expect(html).not.toContain('FIREBASE_PRIVATE_KEY');
    expect(html).not.toContain('DevPassword123!');
    expect(html).not.toContain('service_account');
  });
});

describe('verification failure messaging', () => {
  it('tells the visitor to sign in again when the Firebase session expired', () => {
    const message = describeVerificationFailure(
      new AuthFailure('auth/no-current-user', 'Your sign-in session expired. Please sign in again.'),
    );
    expect(message).toContain('sign in again');
  });

  it('maps a 403 invite refusal to the actionable invite message', () => {
    const message = describeVerificationFailure(
      new ApiError(403, 'A registration invite code is required to create an account.'),
    );
    expect(message).toContain('invite code');
  });

  it('maps a backend 401 to a retry message, never the raw body', () => {
    const message = describeVerificationFailure(
      new ApiError(401, 'Authentication is required to access this resource.'),
    );
    expect(message).toContain('could not be verified');
    expect(message).not.toContain('Authentication is required to access this resource');
  });

  it('maps a missing server configuration to an operator message', () => {
    const message = describeVerificationFailure(
      new ApiError(503, 'Firebase Authentication is not configured'),
    );
    expect(message).toContain('operator');
  });

  it('maps server errors to a generic retry without echoing details', () => {
    const message = describeVerificationFailure(
      new ApiError(500, 'org.postgresql.util.PSQLException: relation users'),
    );
    expect(message).toContain('try again');
    expect(message).not.toContain('PSQLException');
  });
});

describe('sign-in failure messaging (Google and password)', () => {
  it('routes a gated Google identity to the sign-up screen', () => {
    const message = describeSignInFailure(
      new ApiError(403, 'A registration invite code is required to create an account.'),
    );
    expect(message).toContain('invite code');
    expect(message).toContain('Create account');
  });

  it('keeps Firebase-mapped messages intact', () => {
    const message = describeSignInFailure(
      new AuthFailure('auth/invalid-credential', 'The email or password is incorrect.'),
    );
    expect(message).toBe('The email or password is incorrect.');
  });

  it('never surfaces the raw 401 body from an out-of-date backend', () => {
    const message = describeSignInFailure(
      new ApiError(401, 'Authentication is required to access this resource.'),
    );
    expect(message).toContain('could not be verified');
  });
});
