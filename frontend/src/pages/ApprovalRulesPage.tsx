import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import {
  Alert,
  Label,
  Loading,
  PageHeader,
  PageShell,
  PrimaryButton,
  SectionCard,
  StatusPill,
  TextInput,
} from '../components/ui';

/**
 * Approval Rules Settings (Phase 7.1).
 *
 * Lets the owner configure their per-user auto-approval rule:
 *   • enable / disable automatic approval
 *   • set a minimum match score threshold (1–100)
 *
 * The rule can only TOGGLE automatic application creation and RAISE or
 * LOWER the score threshold.  It can NEVER bypass a hard stop, a
 * required-field failure, an artifact-integrity failure, the daily quota,
 * or any other safety gate — those live downstream and are unaffected. *
 * REAL_SUBMIT remains hard-stopped.  This page configures eligibility
 * evaluation rules, not live job-application submission.
 */

interface ApprovalRuleResponse {
  autoApproveEnabled: boolean;
  minScore: number;
  configured: boolean;
}

const DEFAULT_MIN_SCORE = 85;

export const ApprovalRulesPage: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [configured, setConfigured] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  // Form state
  const [enabled, setEnabled] = useState(false);
  const [minScore, setMinScore] = useState(DEFAULT_MIN_SCORE);

  // Validation
  const scoreValid = Number.isInteger(minScore) && minScore >= 1 && minScore <= 100;

  useEffect(() => {
    loadRule();
  }, []);

  const loadRule = async () => {
    setLoading(true);
    try {
      const rule = await apiFetch<ApprovalRuleResponse>('/approval-rules');
      setEnabled(rule.autoApproveEnabled);
      setMinScore(rule.minScore);
      setConfigured(rule.configured);
    } catch {
      setMessage({ type: 'error', text: 'Failed to load approval rules.' });
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!scoreValid) {
      setMessage({ type: 'error', text: 'Minimum score must be a whole number between 1 and 100.' });
      return;
    }
    setSaving(true);
    setMessage(null);
    try {
      await apiFetch('/approval-rules', {
        method: 'PUT',
        body: JSON.stringify({ autoApproveEnabled: enabled, minScore }),
      });
      setConfigured(true);
      setMessage({ type: 'success', text: 'Approval rules saved. Audit log recorded.' });
    } catch (err: unknown) {
      setMessage({ type: 'error', text: err instanceof Error ? err.message : 'Save failed.' });
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <PageShell>
        <Loading>Loading approval rules…</Loading>
      </PageShell>
    );
  }

  return (
    <PageShell>
      <div className="space-y-6 max-w-4xl">
        <PageHeader
          eyebrow="Approval configuration"
          title="Auto-Approval Rules"
          subtitle="Control when Robin automatically approves matched jobs for application preparation. These rules set eligibility thresholds — they never bypass safety gates, quotas, or hard stops."
        />

        {message && (
          <Alert tone={message.type === 'success' ? 'success' : 'error'}>{message.text}</Alert>
        )}

        <form onSubmit={handleSave} className="space-y-6">
          {/* Enable / disable toggle */}
          <SectionCard
            title="Automatic approval"
            actions={
              <StatusPill tone={enabled ? 'emerald' : 'slate'}>
                {enabled ? 'Enabled' : 'Disabled'}
              </StatusPill>
            }
            bodyClassName="space-y-4"
          >
            <div className="flex items-center justify-between gap-4">
              <div className="min-w-0">
                <p className="text-sm font-medium text-ink">
                  Enable automatic approval for high-confidence matches
                </p>
                <p className="mt-0.5 text-xs text-ink-muted">
                  When enabled, jobs that meet your minimum score threshold will be
                  automatically approved for application preparation — subject to your
                  application mode, daily quota, and all safety checks.
                </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={enabled}
                aria-label="Toggle automatic approval"
                onClick={() => setEnabled((prev) => !prev)}
                className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 focus-visible:ring-offset-2 ${
                  enabled ? 'bg-forest-700' : 'bg-ink-faint/30'
                }`}
              >
                <span
                  aria-hidden="true"
                  className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow-lg transition-transform ${
                    enabled ? 'translate-x-5' : 'translate-x-0.5'
                  }`}
                />
              </button>
            </div>

            {!configured && (
              <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
                No custom rule configured yet — Phase 5 defaults are in effect
                (auto-approve high-confidence matches ≥ {DEFAULT_MIN_SCORE} in
                Assisted and Controlled Auto modes).
              </div>
            )}
          </SectionCard>

          {/* Minimum score threshold */}
          <SectionCard
            title="Minimum score threshold"
            actions={
              <StatusPill tone={scoreValid ? 'forest' : 'red'} className="font-mono">
                {minScore} / 100
              </StatusPill>
            }
            bodyClassName="space-y-4"
          >
            <div>
              <Label htmlFor="min-score">Minimum match score for automatic approval</Label>
              <p className="mb-2 text-xs text-ink-muted">
                Only jobs scoring at or above this threshold are eligible for
                automatic approval. Higher values are stricter — fewer jobs
                auto-approve; lower values are more permissive.
              </p>
              <div className="flex items-center gap-4">
                <input
                  type="range"
                  id="min-score-slider"
                  min="1"
                  max="100"
                  value={minScore}
                  onChange={(e) => setMinScore(Number(e.target.value))}
                  aria-label="Minimum score slider"
                  className="flex-1 cursor-pointer accent-forest-700"
                />
                <TextInput
                  id="min-score"
                  type="number"
                  min={1}
                  max={100}
                  step={1}
                  value={minScore}
                  onChange={(e) => setMinScore(Number(e.target.value))}
                  className="!w-20 text-center font-mono"
                  aria-label="Minimum score input"
                />
              </div>
              {!scoreValid && (
                <p role="alert" className="mt-2 text-xs text-red-700">
                  Score must be a whole number between 1 and 100.
                </p>
              )}
            </div>
          </SectionCard>

          {/* Decision mode explainer */}
          <SectionCard title="How decision modes interact with your rule" bodyClassName="space-y-3">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              {[
                {
                  mode: 'MANUAL',
                  title: 'Manual',
                  desc: 'All matches go to the review queue regardless of score. Your auto-approval rule has no effect in this mode.',
                  effect: 'No auto-approval',
                },
                {
                  mode: 'ASSISTED',
                  title: 'Assisted',
                  desc: 'Only matches at or above your minimum score auto-approve. Below-threshold matches go to the review queue.',
                  effect: 'Threshold applies',
                },
                {
                  mode: 'CONTROLLED_AUTO',
                  title: 'Controlled Auto',
                  desc: 'All APPLY-level matches auto-approve, but your minimum score can make the bar stricter. Disabling your rule stops all auto-approval.',
                  effect: 'Full auto (with rule)',
                },
              ].map(({ mode, title, desc, effect }) => (
                <div
                  key={mode}
                  className="rounded-xl border border-line bg-surface p-4 text-left"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-bold text-ink">{title}</span>
                    <StatusPill tone={mode === 'MANUAL' ? 'slate' : mode === 'ASSISTED' ? 'amber' : 'forest'}>
                      {effect}
                    </StatusPill>
                  </div>
                  <p className="mt-1 text-[11px] leading-relaxed text-ink-muted">{desc}</p>
                </div>
              ))}
            </div>
            <p className="text-xs text-ink-muted">
              Your application mode is configured on the{' '}
              <a href="/preferences" className="font-medium text-forest-700 underline hover:text-forest-900">
                Preferences
              </a>{' '}
              page. The mode determines how your auto-approval rule is applied.
            </p>
          </SectionCard>

          {/* Safety notice */}
          <div className="rounded-lg border border-forest-200 bg-forest-50 px-5 py-4 text-xs leading-relaxed text-forest-900">
            <p className="font-semibold">Safety guarantees</p>
            <p className="mt-1">
              Regardless of your settings, Robin will never bypass: hard-filter
              failures, required-field validation, artifact-integrity checks,
              daily application quotas, duplicate-application protection, or the
              REAL_SUBMIT safety gate. These controls are enforced downstream and
              cannot be overridden by approval rules.
            </p>
          </div>

          <PrimaryButton type="submit" disabled={saving || !scoreValid} className="w-full py-3">
            {saving ? 'Saving approval rules…' : 'Save approval rules'}
          </PrimaryButton>
        </form>
      </div>
    </PageShell>
  );
};
