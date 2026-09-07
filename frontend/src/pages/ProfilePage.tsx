import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { Profile } from '../types';

export const ProfilePage: React.FC = () => {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);

  // Purge modal state
  const [showPurgeModal, setShowPurgeModal] = useState(false);
  const [purgePassword, setPurgePassword] = useState('');
  const [purging, setPurging] = useState(false);
  const [purgeError, setPurgeError] = useState<string | null>(null);

  useEffect(() => {
    fetchProfile();
  }, []);

  const fetchProfile = async () => {
    setLoading(true);
    try {
      const p = await apiFetch<Profile>('/profile');
      setProfile(p);
    } catch {
      setProfile(null);
    } finally {
      setLoading(false);
    }
  };

  const handleSaveBasic = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!profile) return;
    setSaving(true);
    setSaveMsg(null);
    try {
      await apiFetch('/profile', {
        method: 'PUT',
        body: JSON.stringify({
          headline: profile.headline,
          phone: profile.phone,
          location: profile.location,
          workEligibility: profile.work_eligibility,
          careerGoals: profile.career_goals,
        }),
      });
      setSaveMsg('Profile saved successfully with audit log entry.');
      fetchProfile();
    } catch (err: unknown) {
      setSaveMsg(err instanceof Error ? err.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const handlePurge = async (e: React.FormEvent) => {
    e.preventDefault();
    setPurging(true);
    setPurgeError(null);
    try {
      await apiFetch('/auth/purge-my-data', {
        method: 'POST',
        body: JSON.stringify({ confirmationPassword: purgePassword }),
      });
      setShowPurgeModal(false);
      setPurgePassword('');
      fetchProfile();
      alert('Your career data has been purged. An immutable audit row has been recorded.');
    } catch (err: unknown) {
      setPurgeError(err instanceof Error ? err.message : 'Purge failed');
    } finally {
      setPurging(false);
    }
  };

  if (loading) {
    return <div className="p-12 text-center text-slate-400 text-sm">Loading career profile...</div>;
  }

  return (
    <div className="space-y-8 max-w-4xl mx-auto">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white tracking-tight">Career Profile Knowledge Base</h1>
          <p className="text-sm text-slate-400">Master CV evidence atoms and eligibility records for automated tailoring</p>
        </div>
        <button
          onClick={() => setShowPurgeModal(true)}
          className="px-3.5 py-2 rounded-lg text-xs font-semibold bg-red-500/10 text-red-400 hover:bg-red-500/20 border border-red-500/20 transition-colors"
        >
          🗑️ Purge My Career Data
        </button>
      </div>

      {saveMsg && (
        <div className="p-3.5 rounded-xl bg-indigo-500/10 border border-indigo-500/20 text-indigo-300 text-xs font-medium">
          {saveMsg}
        </div>
      )}

      {/* Basic Profile & Work Eligibility */}
      <form onSubmit={handleSaveBasic} className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-5">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
          Core Identity &amp; Right to Work
        </h2>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Headline</label>
            <input
              type="text"
              value={profile?.headline || ''}
              onChange={(e) => setProfile(p => p ? { ...p, headline: e.target.value } : null)}
              className="w-full rounded-lg bg-slate-900 border border-slate-700 px-3 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Location</label>
            <input
              type="text"
              value={profile?.location || ''}
              onChange={(e) => setProfile(p => p ? { ...p, location: e.target.value } : null)}
              className="w-full rounded-lg bg-slate-900 border border-slate-700 px-3 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Phone</label>
            <input
              type="text"
              value={profile?.phone || ''}
              onChange={(e) => setProfile(p => p ? { ...p, phone: e.target.value } : null)}
              className="w-full rounded-lg bg-slate-900 border border-slate-700 px-3 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>
        </div>

        <div className="pt-3 border-t border-slate-700/60 grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="flex items-center gap-3 bg-slate-900/60 p-3 rounded-lg border border-slate-700/50">
            <input
              type="checkbox"
              id="rtw"
              checked={profile?.work_eligibility?.right_to_work_uk || false}
              onChange={(e) =>
                setProfile(p =>
                  p
                    ? {
                        ...p,
                        work_eligibility: {
                          ...p.work_eligibility,
                          right_to_work_uk: e.target.checked,
                        },
                      }
                    : null
                )
              }
              className="w-4 h-4 rounded bg-slate-800 border-slate-700 text-indigo-600 focus:ring-indigo-500"
            />
            <label htmlFor="rtw" className="text-xs text-slate-300 font-medium cursor-pointer">
              Right to work in the UK without restriction
            </label>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Visa Requirement Status</label>
            <select
              value={profile?.work_eligibility?.visa_status || 'requires_sponsorship'}
              onChange={(e) =>
                setProfile(p =>
                  p
                    ? {
                        ...p,
                        work_eligibility: {
                          ...p.work_eligibility,
                          visa_status: e.target.value,
                        },
                      }
                    : null
                )
              }
              className="w-full rounded-lg bg-slate-900 border border-slate-700 px-3 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="requires_sponsorship">Requires Skilled Worker Sponsorship</option>
              <option value="graduate_visa">Graduate Visa (Time-limited)</option>
              <option value="settled_status">ILR / Settled Status</option>
              <option value="british_citizen">British Citizen</option>
            </select>
          </div>
        </div>

        <button
          type="submit"
          disabled={saving}
          className="px-5 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold transition-all shadow-md disabled:opacity-50"
        >
          {saving ? 'Saving...' : 'Save Profile Changes'}
        </button>
      </form>

      {/* Skills Section */}
      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
            Skills &amp; Mastery Ratings
          </h2>
          <span className="text-xs text-slate-400">1–5 scale</span>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {profile?.skills && profile.skills.length > 0 ? (
            profile.skills.map((skill) => (
              <div key={skill.id} className="p-3 rounded-lg bg-slate-900/80 border border-slate-700/50 flex justify-between items-center">
                <div>
                  <span className="text-sm font-medium text-white block">{skill.name}</span>
                  <span className="text-[11px] text-slate-400">{skill.category || 'General'}</span>
                </div>
                <div className="text-amber-400 text-xs tracking-widest font-mono">
                  {'★'.repeat(skill.mastery)}{'☆'.repeat(5 - skill.mastery)}
                </div>
              </div>
            ))
          ) : (
            <div className="col-span-3 text-center py-4 text-xs text-slate-500">
              No skills listed yet. Add skills via the API or profile manager.
            </div>
          )}
        </div>
      </div>

      {/* Work Experiences Section */}
      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
          Work Experience &amp; Evidence Atoms
        </h2>

        <div className="space-y-4">
          {profile?.experiences && profile.experiences.length > 0 ? (
            profile.experiences.map((exp) => (
              <div key={exp.id} className="p-4 rounded-lg bg-slate-900/80 border border-slate-700/50 space-y-2">
                <div className="flex justify-between items-start">
                  <div>
                    <h3 className="text-sm font-bold text-white">{exp.title}</h3>
                    <p className="text-xs text-indigo-400 font-medium">{exp.company} • {exp.location || 'UK'}</p>
                  </div>
                  <span className="text-[11px] text-slate-400">
                    {exp.start_month} – {exp.end_month || 'Present'}
                  </span>
                </div>
                {exp.bullets && exp.bullets.length > 0 && (
                  <ul className="list-disc list-inside text-xs text-slate-300 space-y-1 pl-1">
                    {exp.bullets.map((b, idx) => (
                      <li key={idx}>{b.text}</li>
                    ))}
                  </ul>
                )}
              </div>
            ))
          ) : (
            <div className="text-center py-4 text-xs text-slate-500">
              No work experiences added yet.
            </div>
          )}
        </div>
      </div>

      {/* Purge Confirmation Modal */}
      {showPurgeModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4">
          <div className="w-full max-w-md rounded-2xl bg-slate-900 border border-red-500/30 p-6 shadow-2xl space-y-4">
            <h3 className="text-lg font-bold text-red-400">Purge Career Data</h3>
            <p className="text-xs text-slate-300 leading-relaxed">
              This action will cascade-delete all work experiences, skills, education, projects, and preferences.
              Your user account and audit history will be preserved.
            </p>

            {purgeError && (
              <div className="p-2.5 rounded bg-red-500/10 border border-red-500/20 text-red-400 text-xs">
                {purgeError}
              </div>
            )}

            <form onSubmit={handlePurge} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-400 mb-1">
                  Enter Account Password to Confirm
                </label>
                <input
                  type="password"
                  required
                  value={purgePassword}
                  onChange={(e) => setPurgePassword(e.target.value)}
                  placeholder="••••••••"
                  className="w-full rounded-lg bg-slate-800 border border-slate-700 px-3 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-red-500"
                />
              </div>

              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setShowPurgeModal(false)}
                  className="px-4 py-2 rounded-lg text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-300"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={purging}
                  className="px-4 py-2 rounded-lg text-xs font-semibold bg-red-600 hover:bg-red-500 text-white disabled:opacity-50"
                >
                  {purging ? 'Purging...' : 'Confirm Purge'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
