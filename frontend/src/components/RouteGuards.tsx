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
}

export type GateDecision =
  /** Auth state is not known yet — show the loader, never the destination. */
  | 'loading'
  /** Send to /login. */
  | 'redirect-login'
  /** Send into the application. */
  | 'redirect-app'
  /** Render the wrapped children. */
  | 'render';

/**
 * Decision for a route that requires a session.
 *
 * The ordering is the important part: `loading` is checked first, so an
 * unresolved session neither flashes protected content nor bounces a signed-in
 * user who simply reloaded the page.
 */
export function evaluateProtectedRoute(state: AuthGateState): GateDecision {
  if (state.loading) {
    return 'loading';
  }
  return state.authenticated ? 'render' : 'redirect-login';
}

/**
 * Decision for a route that only makes sense when signed out (/login, /signup,
 * /forgot-password). A signed-in user is sent into the application instead of
 * being shown a sign-in form they do not need.
 */
export function evaluatePublicOnlyRoute(state: AuthGateState): GateDecision {
  if (state.loading) {
    return 'loading';
  }
  return state.authenticated ? 'redirect-app' : 'render';
}

/**
 * Renders its children only for a signed-in user. Anyone else is sent to
 * /login, remembering where they were headed so they land there afterwards.
 */
export const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, loading } = useAuth();
  const location = useLocation();

  const decision = evaluateProtectedRoute({ loading, authenticated: user !== null });

  if (decision === 'loading') {
    return <SessionLoader />;
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

/** Renders auth screens only for signed-out visitors. */
export const PublicOnlyRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, loading } = useAuth();

  const decision = evaluatePublicOnlyRoute({ loading, authenticated: user !== null });

  if (decision === 'loading') {
    return <SessionLoader message="Checking your session…" />;
  }

  if (decision === 'redirect-app') {
    return <Navigate to="/" replace />;
  }

  return <>{children}</>;
};
