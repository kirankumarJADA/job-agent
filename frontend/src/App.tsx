import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { Navigation } from './components/Navigation';
import { LoginModal } from './components/LoginModal';

import { DashboardPage } from './pages/DashboardPage';
import { JobsFeedPage } from './pages/JobsFeedPage';
import { JobDetailPage } from './pages/JobDetailPage';
import { ProfilePage } from './pages/ProfilePage';
import { PreferencesPage } from './pages/PreferencesPage';
import { ModelsPage } from './pages/ModelsPage';
import { SourcesPage } from './pages/SourcesPage';
import { LogsPage } from './pages/LogsPage';

const AppLayout: React.FC = () => {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-slate-950 text-slate-400 font-mono text-sm">
        Initializing Job Agent Session...
      </div>
    );
  }

  return (
    <div className="flex min-h-screen bg-slate-950 text-slate-100 antialiased">
      {!user && <LoginModal />}
      <Navigation />
      <main className="flex-1 p-6 lg:p-8 overflow-y-auto max-h-screen">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/jobs" element={<JobsFeedPage />} />
          <Route path="/jobs/:id" element={<JobDetailPage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/preferences" element={<PreferencesPage />} />
          <Route path="/models" element={<ModelsPage />} />
          <Route path="/sources" element={<SourcesPage />} />
          <Route path="/logs" element={<LogsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
};

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <AppLayout />
      </BrowserRouter>
    </AuthProvider>
  );
}
