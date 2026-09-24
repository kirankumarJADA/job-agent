import React from 'react';
import { Navigate, useLocation } from 'react-router-dom';

import { useAuth } from '../context/AuthContext';
import { SessionLoader } from './SessionLoader';

/**
 * Route guards.
 *
 * These are a usability layer, not the security boundary: every protected
 * endpoint is independently authorised by the backend, which derives the caller
 * from a verified Firebase ID token. A user who defeats these guards in the
 * browser still gets 401s from the API — which is the point.
 *
 * The decisions themselves live in the pure functions below so they can be
 * tested exhaustively without rendering a router, which is where the
 * interesting edge cases actually are (the loading state in particular).
 */

export interface AuthGateState {
  /** True until the session and Firebase state have both been resolved. */
  loading: boolean;
  /** True when an application user is signed in. */
  authenticated: boolean;
  /** True when a Firebase account exists but its email is not verified yet. */
  awaitingVerification: boolean;
}

export type GateDecision =
  /** Auth state is not known yet — show the loader, never the destination. */
  | 'loading'
  /** Send to /login. */
  | 'redirect-login'
  /** Send to /verify-email. */
  | 'redirect-verify'
  /** Send into the application. */
  | 'redirect-app'
  /** Render the wrapped children. */
  | 'render';

/**
 * Decision for a route that requires a session.
 *
 * The ordering is the important part: `loading` is checked first, so an
 * unresolved session neither flashes protected content nor bounces a signed-in
 * user who simply reloaded the page. A visitor with a Firebase account whose
 * email is unverified is held at the verification screen instead of being sent
 * to /login — their account exists; only the verification step is missing.
 */
export function evaluateProtectedRoute(state: AuthGateState): GateDecision {
  if (state.loading) {
    return 'loading';
  }
  if (state.authenticated) {
    return 'render';
  }
  return state.awaitingVerification ? 'redirect-verify' : 'redirect-login';
}

/**
 * Decision for a route that only makes sense when signed out (/login, /signup,
 * /forgot-password). A signed-in user is sent into the application instead of
 * being shown a sign-in form they do not need; a visitor waiting on email
 * verification is sent back to the verification screen rather than being
 * allowed to re-register the same address.
 */
export function evaluatePublicOnlyRoute(state: AuthGateState): GateDecision {
  if (state.loading) {
    return 'loading';
  }
  if (state.authenticated) {
    return 'redirect-app';
  }
  return state.awaitingVerification ? 'redirect-verify' : 'render';
}

/**
 * Decision for the verification screen itself. It renders for a visitor with a
 * pending unverified Firebase account, sends a verified-and-signed-in user into
 * the application, and sends anyone else (nothing pending at all) to /login.
 */
export function evaluateVerificationRoute(state: AuthGateState): GateDecision {
  if (state.loading) {
    return 'loading';
  }
  if (state.authenticated) {
    return 'redirect-app';
  }
  return state.awaitingVerification ? 'render' : 'redirect-login';
}

/** Renders its children only for a signed-in user. */
export const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, loading, awaitingVerification } = useAuth();
  const location = useLocation();

  const decision = evaluateProtectedRoute({
    loading,
    authenticated: user !== null,
    awaitingVerification,
  });

  if (decision === 'loading') {
    return <SessionLoader />;
  }

  if (decision === 'redirect-verify') {
    return <Navigate to="/verify-email" replace />;
  }

  if (decision === 'redirect-login') {
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: `${location.pathname}${location.search}` }}
      />
    );
  }

  return <>{children}</>;
};

/** Renders auth screens only for signed-out visitors with nothing pending. */
export const PublicOnlyRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, loading, awaitingVerification } = useAuth();

  const decision = evaluatePublicOnlyRoute({
    loading,
    authenticated: user !== null,
    awaitingVerification,
  });

  if (decision === 'loading') {
    return <SessionLoader message="Checking your session…" />;
  }

  if (decision === 'redirect-app') {
    return <Navigate to="/" replace />;
  }

  if (decision === 'redirect-verify') {
    return <Navigate to="/verify-email" replace />;
  }

  return <>{children}</>;
};

/** Renders the email-verification screen for exactly the right visitor. */
export const VerificationRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, loading, awaitingVerification } = useAuth();

  const decision = evaluateVerificationRoute({
    loading,
    authenticated: user !== null,
    awaitingVerification,
  });

  if (decision === 'loading') {
    return <SessionLoader message="Checking your verification status…" />;
  }

  if (decision === 'redirect-app') {
    return <Navigate to="/" replace />;
  }

  if (decision === 'redirect-login') {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
};
