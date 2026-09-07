import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { PreferenceSet, ScoringWeights } from '../types';

export const PreferencesPage: React.FC = () => {
  const [prefs, setPrefs] = useState<PreferenceSet | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  // Form inputs
  const [titlesStr, setTitlesStr] = useState('');
  const [skillsStr, setSkillsStr] = useState('');
  const [salaryMin, setSalaryMin] = useState<number>(60000);
  const [sponsorshipPolicy, setSponsorshipPolicy] = useState<PreferenceSet['sponsorship_policy']>('SHOW_ALL');
  const [appMode, setAppMode] = useState<PreferenceSet['application_mode']>('ASSISTED');
  const [remoteTypes, setRemoteTypes] = useState<string[]>(['REMOTE', 'HYBRID']);

  const [weights, setWeights] = useState<ScoringWeights>({
    skill: 30,
    experience: 15,
    visa: 20,
    location: 10,
    salary: 10,
    career: 10,
    difficulty: 5,
  });

  const weightSum =
    (weights.skill || 0) +
    (weights.experience || 0) +
    (weights.visa || 0) +
    (weights.location || 0) +
    (weights.salary || 0) +
    (weights.career || 0) +
    (weights.difficulty || 0);

  const isWeightValid = weightSum === 100;

  useEffect(() => {
    fetchPreferences();
  }, []);

  const fetchPreferences = async () => {
    setLoading(true);
    try {
      const p = await apiFetch<PreferenceSet>('/preferences');
      setPrefs(p);
      setTitlesStr((p.titles || []).join(', '));
      setSkillsStr((p.required_skills || []).join(', '));
      setSalaryMin(p.salary_min_gbp || 60000);
      setSponsorshipPolicy(p.sponsorship_policy || 'SHOW_ALL');
      setAppMode(p.application_mode || 'ASSISTED');
      setRemoteTypes(p.remote_types || ['REMOTE', 'HYBRID']);
      if (p.scoring_weights) {
        setWeights(p.scoring_weights);
      }
    } catch {
      setPrefs(null);
    } finally {
      setLoading(false);
    }
  };

  const handleWeightChange = (key: keyof ScoringWeights, val: number) => {
    setWeights((prev) => ({ ...prev, [key]: val }));
  };

  const toggleRemote = (type: string) => {
    setRemoteTypes((prev) =>
      prev.includes(type) ? prev.filter((t) => t !== type) : [...prev, type]
    );
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isWeightValid) {
      setMessage({ type: 'error', text: `Scoring weights must sum to exactly 100 (current: ${weightSum})` });
      return;
    }

    setSaving(true);
    setMessage(null);

    const titles = titlesStr
      .split(',')
      .map((t) => t.trim())
      .filter(Boolean);

    const requiredSkills = skillsStr
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);

    try {
      await apiFetch('/preferences', {
        method: 'PUT',
        body: JSON.stringify({
          titles,
          keywordsInclude: prefs?.keywords_include || [],
          keywordsExclude: prefs?.keywords_exclude || [],
          requiredSkills,
          locationsAllowed: ['UK'],
          remoteTypes,
          employmentTypes: ['FULL_TIME'],
          salaryMinGbp: salaryMin,
          sponsorshipPolicy,
          applicationMode: appMode,
          scoringWeights: weights,
        }),
      });
      setMessage({ type: 'success', text: 'Preferences updated successfully! Audit log recorded.' });
      fetchPreferences();
    } catch (err: unknown) {
      setMessage({ type: 'error', text: err instanceof Error ? err.message : 'Save failed' });
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="p-12 text-center text-slate-400 text-sm">Loading job preferences...</div>;
  }

  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-white tracking-tight">Job Search Preferences</h1>
        <p className="text-sm text-slate-400">Configure discovery filters, sponsorship requirements, and scoring weight vectors</p>
      </div>

      {message && (
        <div
          className={`p-4 rounded-xl text-xs font-semibold ${
            message.type === 'success'
              ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
              : 'bg-red-500/10 text-red-400 border border-red-500/20'
          }`}
        >
          {message.text}
        </div>
      )}

      <form onSubmit={handleSave} className="space-y-6">
        {/* Core Search Scope */}
        <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
            Target Job Criteria
          </h2>

          <div className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-slate-400 mb-1">
                Target Job Titles (comma-separated)
              </label>
              <input
                type="text"
                value={titlesStr}
                onChange={(e) => setTitlesStr(e.target.value)}
                placeholder="Senior Backend Engineer, Platform Engineer, Software Architect"
                className="w-full rounded-lg bg-slate-900 border border-slate-700 px-3.5 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-400 mb-1">
                Required Technical Skills (comma-separated)
              </label>
              <input
                type="text"
                value={skillsStr}
                onChange={(e) => setSkillsStr(e.target.value)}
                placeholder="Java, Kotlin, Spring Boot, Kubernetes, AWS"
                className="w-full rounded-lg bg-slate-900 border border-slate-700 px-3.5 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2">
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1">
                  Minimum Base Salary (£ GBP / Year)
                </label>
                <input
                  type="number"
                  step="5000"
                  value={salaryMin}
                  onChange={(e) => setSalaryMin(Number(e.target.value))}
                  className="w-full rounded-lg bg-slate-900 border border-slate-700 px-3.5 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1">
                  Sponsorship Requirement Policy
                </label>
                <select
                  value={sponsorshipPolicy}
                  onChange={(e) => setSponsorshipPolicy(e.target.value as PreferenceSet['sponsorship_policy'])}
                  className="w-full rounded-lg bg-slate-900 border border-slate-700 px-3.5 py-2 text-sm text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="SHOW_ALL">Show All Jobs (Informational Only)</option>
                  <option value="SPONSORSHIP_REQUIRED">Sponsorship Required (Filter Out Non-Sponsors)</option>
                  <option value="SPONSORSHIP_PREFERRED">Sponsorship Preferred (Prioritize Sponsors)</option>
                  <option value="SPONSORSHIP_NOT_REQUIRED">Sponsorship Not Required</option>
                </select>
              </div>
            </div>

            <div className="pt-2">
              <label className="block text-xs font-medium text-slate-400 mb-2">Workplace Preferences</label>
              <div className="flex gap-4">
                {['REMOTE', 'HYBRID', 'ONSITE'].map((type) => (
                  <label key={type} className="inline-flex items-center gap-2 text-xs text-slate-300 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={remoteTypes.includes(type)}
                      onChange={() => toggleRemote(type)}
                      className="rounded bg-slate-800 border-slate-700 text-indigo-600 focus:ring-indigo-500"
                    />
                    {type}
                  </label>
                ))}
              </div>
            </div>
          </div>
        </div>

        {/* Scoring Weights Section with Live Validation */}
        <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
                Scoring Weight Vector
              </h2>
              <p className="text-xs text-slate-400">All weight points must sum to exactly 100%</p>
            </div>
            <div
              className={`px-3 py-1 rounded-full text-xs font-mono font-bold ${
                isWeightValid
                  ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                  : 'bg-red-500/10 text-red-400 border border-red-500/20'
              }`}
            >
              Sum: {weightSum} / 100
            </div>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2">
            {[
              { key: 'skill', label: 'Technical Skill Match', max: 50 },
              { key: 'visa', label: 'Visa Sponsorship Availability', max: 50 },
              { key: 'experience', label: 'Years of Experience Match', max: 30 },
              { key: 'location', label: 'Location & Remote Alignment', max: 30 },
              { key: 'salary', label: 'Salary vs Target', max: 30 },
              { key: 'career', label: 'Career Growth Goals', max: 30 },
              { key: 'difficulty', label: 'Application Friction / Effort', max: 20 },
            ].map(({ key, label, max }) => {
              const weightKey = key as keyof ScoringWeights;
              const val = weights[weightKey] || 0;
              return (
                <div key={key} className="space-y-1 bg-slate-900/60 p-3 rounded-lg border border-slate-800">
                  <div className="flex justify-between text-xs">
                    <span className="text-slate-300 font-medium">{label}</span>
                    <span className="font-mono text-indigo-400 font-bold">{val} pts</span>
                  </div>
                  <input
                    type="range"
                    min="0"
                    max={max}
                    value={val}
                    onChange={(e) => handleWeightChange(weightKey, Number(e.target.value))}
                    className="w-full accent-indigo-500 cursor-pointer"
                  />
                </div>
              );
            })}
          </div>
        </div>

        {/* Application Mode Selection */}
        <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-3">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
            Autonomous Application Mode
          </h2>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            {[
              { mode: 'MANUAL', title: 'Manual', desc: 'Discovery only; user submits all applications manually.' },
              { mode: 'ASSISTED', title: 'Assisted (Recommended)', desc: 'Tailors CV & prepares interaction plan; requires user sign-off.' },
              { mode: 'CONTROLLED_AUTO', title: 'Controlled Auto', desc: 'Autonomous submission within strict policy limits and kill-switches.' },
            ].map(({ mode, title, desc }) => (
              <div
                key={mode}
                onClick={() => setAppMode(mode as PreferenceSet['application_mode'])}
                className={`p-4 rounded-xl border cursor-pointer transition-all ${
                  appMode === mode
                    ? 'bg-indigo-600/15 border-indigo-500 text-white'
                    : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:border-slate-700'
                }`}
              >
                <div className="font-bold text-sm mb-1">{title}</div>
                <div className="text-[11px] leading-relaxed text-slate-400">{desc}</div>
              </div>
            ))}
          </div>
        </div>

        <button
          type="submit"
          disabled={saving || !isWeightValid}
          className="w-full py-3 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-semibold text-sm shadow-lg shadow-indigo-600/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {saving ? 'Saving Preferences...' : 'Save Job Search Preferences'}
        </button>
      </form>
    </div>
  );
};
