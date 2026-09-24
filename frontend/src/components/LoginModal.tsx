import React, { useState } from 'react';
import { useAuth } from '../context/AuthContext';

export const LoginModal: React.FC = () => {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Invalid credentials');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="w-full max-w-md rounded-2xl bg-slate-900 p-8 shadow-2xl border border-slate-800">
        <div className="text-center mb-6">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-xl bg-indigo-500/10 text-indigo-400 mb-3 font-mono font-bold text-xl">
            AI
          </div>
          <h2 className="text-2xl font-bold text-white tracking-tight">Personal AI Job Agent</h2>
          <p className="text-sm text-slate-400 mt-1">Sign in to access your autonomous job portal</p>
        </div>

        {error && (
          <div className="mb-4 p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-sm">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-1">
              Email Address
            </label>
            <input
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="user@example.com"
              className="w-full rounded-lg bg-slate-800 border border-slate-700 px-3.5 py-2.5 text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-1">
              Password
            </label>
            <input
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              className="w-full rounded-lg bg-slate-800 border border-slate-700 px-3.5 py-2.5 text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <button
            type="submit"
            disabled={submitting}
            className="w-full py-2.5 px-4 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-medium shadow-lg shadow-indigo-600/20 transition-all disabled:opacity-50"
          >
            {submitting ? 'Authenticating...' : 'Sign In'}
          </button>
        </form>

        {/*
          Dev-only convenience control.
          `import.meta.env.DEV` is replaced with the literal `false` by Vite in
          production builds, so this entire block (including the credentials) is
          eliminated from the production bundle. The credentials must stay inline
          inside this guarded block so they cannot survive tree-shaking.
        */}
        {import.meta.env.DEV && (
          <div className="mt-6 pt-6 border-t border-slate-800 flex justify-between items-center text-xs">
            <span className="text-slate-500">Local Dev Mode</span>
            <button
              type="button"
              onClick={() => {
                setEmail('dev@example.local');
                setPassword('DevPassword123!');
              }}
              className="text-indigo-400 hover:text-indigo-300 font-medium transition-colors underline"
            >
              Auto-fill Dev Credentials
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
