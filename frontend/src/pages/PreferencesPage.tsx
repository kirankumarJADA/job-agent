import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { PreferenceSet, ScoringWeights } from '../types';
import {
  Alert,
  Label,
  Loading,
  PageHeader,
  PageShell,
  PrimaryButton,
  SectionCard,
  Select,
  StatusPill,
  TextInput,
} from '../components/ui';

/**
 * Job Search Preferences.
 *
 * Presentation-only redesign: identical fields, validation rules and PUT
 * /preferences payload. The scoring-weight sliders keep the live sum-to-100
 * check; the application-mode selector is now a card group.
 */
export const PreferencesPage: React.FC = () => {
  const [prefs, setPrefs] = useState<PreferenceSet | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  // Form inputs
  const [titlesStr, setTitlesStr] = useState('');
  const [skillsStr, setSkillsStr] = useState('');
  const [salaryMin, setSalaryMin] = useState<number>(60000);
  const [sponsorshipPolicy, setSponsorshipPolicy] = useState<PreferenceSet['sponsorshipPolicy']>('SHOW_ALL');
  const [appMode, setAppMode] = useState<PreferenceSet['applicationMode']>('ASSISTED');
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
      setSkillsStr((p.requiredSkills || []).join(', '));
      setSalaryMin(p.salaryMinGbp || 60000);
      setSponsorshipPolicy(p.sponsorshipPolicy || 'SHOW_ALL');
      setAppMode(p.applicationMode || 'ASSISTED');
      setRemoteTypes(p.remoteTypes || ['REMOTE', 'HYBRID']);
      if (p.scoringWeights) {
        setWeights(p.scoringWeights);
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
          keywordsInclude: prefs?.keywordsInclude || [],
          keywordsExclude: prefs?.keywordsExclude || [],
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
    return <PageShell><Loading>Loading job preferences…</Loading></PageShell>;
  }

  return (
    <PageShell>
      <div className="space-y-6 max-w-4xl">
        <PageHeader
          eyebrow="Tune the agent"
          title="Job Search Preferences"
          subtitle="Configure discovery filters, sponsorship requirements, and the scoring weight vector the agent ranks jobs with."
        />

        {message && <Alert tone={message.type === 'success' ? 'success' : 'error'}>{message.text}</Alert>}

        <form onSubmit={handleSave} className="space-y-6">
          {/* Target job criteria */}
          <SectionCard title="Target job criteria" bodyClassName="space-y-5">
            <div>
              <Label htmlFor="pref-titles">Target job titles (comma-separated)</Label>
              <TextInput
                id="pref-titles"
                type="text"
                value={titlesStr}
                onChange={(e) => setTitlesStr(e.target.value)}
                placeholder="Senior Backend Engineer, Platform Engineer, Software Architect"
              />
            </div>

            <div>
              <Label htmlFor="pref-skills">Required technical skills (comma-separated)</Label>
              <TextInput
                id="pref-skills"
                type="text"
                value={skillsStr}
                onChange={(e) => setSkillsStr(e.target.value)}
                placeholder="Java, Kotlin, Spring Boot, Kubernetes, AWS"
              />
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <Label htmlFor="pref-salary">Minimum base salary (£ GBP / year)</Label>
                <TextInput
                  id="pref-salary"
                  type="number"
                  step="5000"
                  value={salaryMin}
                  onChange={(e) => setSalaryMin(Number(e.target.value))}
                />
              </div>

              <div>
                <Label htmlFor="pref-sponsorship">Sponsorship requirement policy</Label>
                <Select
                  id="pref-sponsorship"
                  value={sponsorshipPolicy}
                  onChange={(e) => setSponsorshipPolicy(e.target.value as PreferenceSet['sponsorshipPolicy'])}
                >
                  <option value="SHOW_ALL">Show all jobs (informational only)</option>
                  <option value="SPONSORSHIP_REQUIRED">Sponsorship required (filter out non-sponsors)</option>
                  <option value="SPONSORSHIP_PREFERRED">Sponsorship preferred (prioritize sponsors)</option>
                  <option value="SPONSORSHIP_NOT_REQUIRED">Sponsorship not required</option>
                </Select>
              </div>
            </div>

            <fieldset>
              <legend className="mb-2 text-xs font-semibold text-ink-soft">Workplace preferences</legend>
              <div className="flex flex-wrap gap-2">
                {['REMOTE', 'HYBRID', 'ONSITE'].map((type) => {
                  const selected = remoteTypes.includes(type);
                  return (
                    <button
                      key={type}
                      type="button"
                      aria-pressed={selected}
                      onClick={() => toggleRemote(type)}
                      className={`rounded-full border px-4 py-1.5 text-xs font-semibold transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 ${
                        selected
                          ? 'border-forest-700 bg-forest-900 text-cream-50'
                          : 'border-line bg-surface text-ink-soft hover:border-forest-300 hover:bg-forest-50'
                      }`}
                    >
                      {type}
                    </button>
                  );
                })}
              </div>
            </fieldset>
          </SectionCard>

          {/* Scoring weights with live validation */}
          <SectionCard
            title="Scoring weight vector"
            hint="All weight points must sum to exactly 100"
            actions={
              <StatusPill tone={isWeightValid ? 'emerald' : 'red'} className="font-mono">
                Sum: {weightSum} / 100
              </StatusPill>
            }
            bodyClassName="space-y-4"
          >
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {[
                { key: 'skill', label: 'Technical skill match', max: 50 },
                { key: 'visa', label: 'Visa sponsorship availability', max: 50 },
                { key: 'experience', label: 'Years of experience match', max: 30 },
                { key: 'location', label: 'Location & remote alignment', max: 30 },
                { key: 'salary', label: 'Salary vs target', max: 30 },
                { key: 'career', label: 'Career growth goals', max: 30 },
                { key: 'difficulty', label: 'Application friction / effort', max: 20 },
              ].map(({ key, label, max }) => {
                const weightKey = key as keyof ScoringWeights;
                const val = weights[weightKey] || 0;
                return (
                  <div key={key} className="rounded-lg border border-line bg-cream-50/70 p-3">
                    <div className="flex items-center justify-between text-xs">
                      <span className="font-medium text-ink-soft">{label}</span>
                      <span className="font-mono font-bold text-forest-700">{val} pts</span>
                    </div>
                    <input
                      type="range"
                      min="0"
                      max={max}
                      value={val}
                      aria-label={`${label} weight`}
                      onChange={(e) => handleWeightChange(weightKey, Number(e.target.value))}
                      className="mt-2 w-full cursor-pointer accent-forest-700"
                    />
                  </div>
                );
              })}
            </div>
          </SectionCard>

          {/* Application mode */}
          <SectionCard title="Autonomous application mode" bodyClassName="space-y-3">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              {[
                { mode: 'MANUAL', title: 'Manual', desc: 'Discovery only; user submits all applications manually.' },
                { mode: 'ASSISTED', title: 'Assisted (Recommended)', desc: 'Tailors CV & prepares interaction plan; requires user sign-off.' },
                { mode: 'CONTROLLED_AUTO', title: 'Controlled Auto', desc: 'Autonomous submission within strict policy limits and kill-switches.' },
              ].map(({ mode, title, desc }) => {
                const selected = appMode === mode;
                return (
                  <button
                    key={mode}
                    type="button"
                    aria-pressed={selected}
                    onClick={() => setAppMode(mode as PreferenceSet['applicationMode'])}
                    className={`rounded-xl border p-4 text-left transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 ${
                      selected
                        ? 'border-forest-700 bg-forest-50 ring-1 ring-forest-700'
                        : 'border-line bg-surface hover:border-forest-300 hover:bg-forest-50/50'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className={`text-sm font-bold ${selected ? 'text-forest-900' : 'text-ink'}`}>{title}</span>
                      {selected && <span aria-hidden="true" className="h-2 w-2 rounded-full bg-forest-700" />}
                    </div>
                    <div className="mt-1 text-[11px] leading-relaxed text-ink-muted">{desc}</div>
                  </button>
                );
              })}
            </div>
          </SectionCard>

          <PrimaryButton type="submit" disabled={saving || !isWeightValid} className="w-full py-3">
            {saving ? 'Saving preferences…' : 'Save job search preferences'}
          </PrimaryButton>
        </form>
      </div>
    </PageShell>
  );
};
