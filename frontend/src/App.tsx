import React from 'react';
import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom';

import { AuthProvider } from './context/AuthContext';
import { Navigation } from './components/Navigation';
import { ProtectedRoute, PublicOnlyRoute, VerificationRoute } from './components/RouteGuards';

import { DashboardPage } from './pages/DashboardPage';
import { JobsFeedPage } from './pages/JobsFeedPage';
import { JobDetailPage } from './pages/JobDetailPage';
import { ProfilePage } from './pages/ProfilePage';
import { PreferencesPage } from './pages/PreferencesPage';
import { ModelsPage } from './pages/ModelsPage';
import { SourcesPage } from './pages/SourcesPage';
import { LogsPage } from './pages/LogsPage';

import { LoginPage } from './pages/LoginPage';
import { SignUpPage } from './pages/SignUpPage';
import { VerifyEmailPage } from './pages/VerifyEmailPage';
import { ForgotPasswordPage } from './pages/ForgotPasswordPage';

/**
 * The authenticated application shell: sidebar plus content area. Rendered only
 * for a signed-in user (see the guard on the layout route below), so the
 * previous "blocking login modal over the whole app" arrangement is gone —
 * signing in is now a route, which is also what makes /login linkable and
 * bookmarkable.
 */
const AppShell: React.FC = () => (
  <div className="flex min-h-screen bg-slate-950 text-slate-100 antialiased">
    <Navigation />
    <main className="flex-1 p-6 lg:p-8 overflow-y-auto max-h-screen">
      <Outlet />
    </main>
  </div>
);

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          {/*
            Public auth routes. PublicOnlyRoute keeps signed-in users out of
            them, so /login is not a dead end once you have an account.
          */}
          <Route
            path="/login"
            element={
              <PublicOnlyRoute>
                <LoginPage />
              </PublicOnlyRoute>
            }
          />
          <Route
            path="/signup"
            element={
              <PublicOnlyRoute>
                <SignUpPage />
              </PublicOnlyRoute>
            }
          />
          <Route
            path="/forgot-password"
            element={
              <PublicOnlyRoute>
                <ForgotPasswordPage />
              </PublicOnlyRoute>
            }
          />
          {/*
            The email-verification waiting room. Reachable only by a visitor
            with a pending unverified Firebase account (see VerificationRoute);
            after verification establishes the session it forwards into the app.
          */}
          <Route
            path="/verify-email"
            element={
              <VerificationRoute>
                <VerifyEmailPage />
              </VerificationRoute>
            }
          />

          {/*
            Everything else requires a session. The pathless layout route wraps
            the whole authenticated area, so protection is applied once rather
            than repeated on each page.
          */}
          <Route
            element={
              <ProtectedRoute>
                <AppShell />
              </ProtectedRoute>
            }
          >
            <Route path="/" element={<DashboardPage />} />
            <Route path="/jobs" element={<JobsFeedPage />} />
            <Route path="/jobs/:id" element={<JobDetailPage />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/preferences" element={<PreferencesPage />} />
            <Route path="/models" element={<ModelsPage />} />
            <Route path="/sources" element={<SourcesPage />} />
            <Route path="/logs" element={<LogsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}
