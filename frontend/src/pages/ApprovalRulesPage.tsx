import React, { useCallback, useEffect, useState } from 'react';
import { ApiError, apiFetch } from '../api/client';
import type { ApplicationMode, ApprovalRule, ApprovalRuleAvailability } from '../types';
import {
  Alert,
  Label,
  Loading,
  PageHeader,
  PageShell,
  PrimaryButton,
  SecondaryButton,
  SectionCard,
  StatusPill,
  TextInput,
} from '../components/ui';

/**
 * Approval Rules Settings (Phase 7.1, health states added in 7.3).
 *
 * Lets the owner configure their per-user auto-approval rule:
 *   • enable / disable automatic approval
 *   • set a minimum match score threshold (1–100)
 *
 * The backend reports one of three availability states, and this page renders
 * each honestly:
 *
 *   • NOT CONFIGURED (ABSENT) — no rule row exists. The application mode alone
 *     decides: Manual and Controlled Auto send every match to review; Assisted
 *     uses the backend's own score floor.
 *   • DISABLED — the owner saved a rule with automatic approval off. Every
 *     match queues for review in both automatic modes.
 *   • ENABLED (CONFIGURED) — matches meeting the threshold may be approved
 *     automatically.
 *   • UNAVAILABLE (UNREADABLE) — the rule could not be read, so the backend is
 *     failing closed and automatic approval is paused. This arrives as HTTP 503;
 *     the form is hidden rather than showing settings that were not loaded, and
 *     the owner gets a retry.
 *
 * The rule can only TOGGLE automatic application creation and RAISE or LOWER
 * the score threshold. It can NEVER bypass a hard stop, a required-field
 * failure, an artifact-integrity failure, the daily quota, duplicate
 * protection, or any other safety gate — those live downstream and are
 * unaffected. REAL_SUBMIT remains hard-stopped: this page configures
 * eligibility evaluation, not live job-application submission.
 */

/** Fallback floor used only before the backend reports its own. */
const DEFAULT_MIN_SCORE = 85;

/** What the owner will actually see, per mode, when no rule is saved. */
function absentBehaviour(mode: ApplicationMode | null, assistedFloor: number): string {
  switch (mode) {
    case 'MANUAL':
      return `Manual mode sends every match to the review queue, so a rule would have no effect until you switch modes.`;
    case 'CONTROLLED_AUTO':
      return `Controlled Auto needs an enabled rule. With no rule saved, every match goes to the review queue.`;
    case 'ASSISTED':
    default:
      return `Assisted mode auto-approves matches scoring ${assistedFloor} or higher until you save a rule.`;
  }
}

export const ApprovalRulesPage: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  // Availability as last reported by the backend. null means "not resolved".
  const [availability, setAvailability] = useState<ApprovalRuleAvailability | null>(null);
  const [unreadableMessage, setUnreadableMessage] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [applicationMode, setApplicationMode] = useState<ApplicationMode | null>(null);
  const [assistedFloor, setAssistedFloor] = useState(DEFAULT_MIN_SCORE);

  const [configured, setConfigured] = useState(false);
  const [enabled, setEnabled] = useState(false);
  const [minScore, setMinScore] = useState(DEFAULT_MIN_SCORE);
  const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const scoreValid = Number.isInteger(minScore) && minScore >= 1 && minScore <= 100;
  // A freshly-loaded form that has not been touched must not be savable while
  // no rule exists yet: saving it would persist the default (disabled) state
  // and silently create a rule the owner never chose.
  const canSave = scoreValid && (configured || dirty);
  const ruleResolved = availability === 'CONFIGURED' || availability === 'ABSENT';

  const loadRule = useCallback(async () => {
    setLoading(true);
    setMessage(null);
    setLoadError(null);
    setUnreadableMessage(null);
    try {
      const rule = await apiFetch<ApprovalRule>('/approval-rules');
      setAvailability(rule.availability);
      setConfigured(rule.configured);
      setEnabled(rule.autoApproveEnabled);
      setMinScore(rule.minScore);
      setApplicationMode(rule.applicationMode ?? null);
      setAssistedFloor(rule.assistedFloor ?? DEFAULT_MIN_SCORE);
      setDirty(false);
    } catch (err: unknown) {
      // An unreadable rule is a distinguishable 503, not a generic outage, and
      // it means the backend is already withholding automatic approval.
      if (err instanceof ApiError && err.status === 503) {
        setAvailability('UNREADABLE');
        setUnreadableMessage(err.message);
      } else {
        // Any other failure leaves the rule unresolved. Drop the resolved state
        // so the form cannot show settings that were never loaded.
        setAvailability(null);
        setLoadError(err instanceof Error ? err.message : 'Failed to load approval rules.');
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRule();
  }, [loadRule]);

  const updateEnabled = (next: boolean) => {
    setEnabled(next);
    setDirty(true);
  };

  const updateMinScore = (next: number) => {
    setMinScore(next);
    setDirty(true);
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!scoreValid) {
      setMessage({ type: 'error', text: 'Minimum score must be a whole number between 1 and 100.' });
      return;
    }
    if (!canSave) {
      setMessage({
        type: 'error',
        text: 'Change a setting first — saving the untouched default would store a disabled rule.',
      });
      return;
    }
    setSaving(true);
    setMessage(null);
    try {
      const saved = await apiFetch<ApprovalRule>('/approval-rules', {
        method: 'PUT',
        body: JSON.stringify({ autoApproveEnabled: enabled, minScore }),
      });
      setAvailability('CONFIGURED');
      setConfigured(true);
      setEnabled(saved.autoApproveEnabled);
      setMinScore(saved.minScore);
      setDirty(false);
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

  const refreshButton = (
    <SecondaryButton type="button" onClick={() => void loadRule()} disabled={loading}>
      {loading ? 'Refreshing…' : 'Refresh'}
    </SecondaryButton>
  );

  // ── Unreadable: the backend is failing closed. No form, no stale settings. ──
  if (availability === 'UNREADABLE') {
    return (
      <PageShell>
        <div className="space-y-6 max-w-4xl">
          <PageHeader
            eyebrow="Approval configuration"
            title="Auto-Approval Rules"
            subtitle="Control when Robin automatically approves matched jobs for application preparation."
            actions={refreshButton}
          />
          <div className="rounded-lg border border-red-200 bg-red-50 px-5 py-4 text-sm leading-relaxed text-red-800">
            <p className="font-semibold">Your approval rule could not be read</p>
            <p className="mt-1">
              {unreadableMessage ??
                'Robin could not read your approval rule, so automatic approval is paused for safety and matches wait for your review.'}
            </p>
            <p className="mt-2">
              Automatic approval has been disabled for safety while this lasts. Eligible
              opportunities will require human review. Refresh to try again.
            </p>
          </div>
          <div className="rounded-lg border border-line bg-surface-sunken px-5 py-4 text-xs leading-relaxed text-ink-soft">
            <p className="font-semibold text-ink">Why you are seeing this</p>
            <p className="mt-1">
              A rule that cannot be read is never treated as an absent or disabled rule, and it
              never authorises automatic approval. Settings are hidden rather than shown from a
              stale load, so nothing here is a guess.
            </p>
          </div>
        </div>
      </PageShell>
    );
  }

  // ── Load failed outright: also unresolved, also no form. ──
  if (loadError) {
    return (
      <PageShell>
        <div className="space-y-6 max-w-4xl">
          <PageHeader
            eyebrow="Approval configuration"
            title="Auto-Approval Rules"
            subtitle="Control when Robin automatically approves matched jobs for application preparation."
            actions={refreshButton}
          />
          <Alert tone="error">
            {loadError} Your saved approval rules are unaffected; this page just could not read
            them.
          </Alert>
        </div>
      </PageShell>
    );
  }

  const statusLabel = !ruleResolved || !configured
    ? 'Not configured'
    : enabled
      ? 'Enabled'
      : 'Disabled';

  return (
    <PageShell>
      <div className="space-y-6 max-w-4xl">
        <PageHeader
          eyebrow="Approval configuration"
          title="Auto-Approval Rules"
          subtitle="Control when Robin automatically approves matched jobs for application preparation. These rules set eligibility thresholds — they never bypass safety gates, quotas, or hard stops."
          actions={refreshButton}
        />

        {message && (
          <Alert tone={message.type === 'success' ? 'success' : 'error'}>{message.text}</Alert>
        )}

        <form onSubmit={handleSave} className="space-y-6">
          {/* Enable / disable toggle */}
          <SectionCard
            title="Automatic approval"
            actions={
              <StatusPill tone={configured && enabled ? 'emerald' : 'slate'}>{statusLabel}</StatusPill>
            }
            bodyClassName="space-y-4"
          >
            <div className="flex items-center justify-between gap-4">
              <div className="min-w-0">
                <p className="text-sm font-medium text-ink">
                  Enable automatic approval for high-confidence matches
                </p>
                <p className="mt-0.5 text-xs text-ink-muted">
                  When enabled, jobs that meet your minimum score threshold may be
                  automatically approved for application preparation — subject to your
                  application mode, daily quota, and all safety checks. Enabling a rule
                  never overrides a safety check or the quota.
                </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={enabled}
                aria-label="Toggle automatic approval"
                onClick={() => updateEnabled(!enabled)}
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

            {!configured ? (
              <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
                <p className="font-semibold">No custom rule is saved yet.</p>
                <p className="mt-1">
                  Until you save one, your application mode alone decides what happens to a
                  match. {absentBehaviour(applicationMode, assistedFloor)} Save a rule below to
                  change this.
                </p>
              </div>
            ) : !enabled ? (
              <div className="rounded-lg border border-line bg-surface-sunken px-4 py-3 text-xs text-ink-muted">
                Automatic approval is <span className="font-medium">disabled</span> by your saved
                rule. Every match goes to the review queue in every mode. Toggle the switch on and
                save to re-enable it.
              </div>
            ) : null}
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
                  onChange={(e) => updateMinScore(Number(e.target.value))}
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
                  onChange={(e) => updateMinScore(Number(e.target.value))}
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
                  desc: 'Every match goes to the review queue regardless of score. Your auto-approval rule has no effect in this mode.',
                  effect: 'Review only',
                },
                {
                  mode: 'ASSISTED',
                  title: 'Assisted',
                  desc: `Matches at or above your minimum score auto-approve; everything else goes to review. With no rule saved, the backend floor is ${assistedFloor}. A disabled rule sends everything to review.`,
                  effect: `Threshold ≥ ${assistedFloor}`,
                },
                {
                  mode: 'CONTROLLED_AUTO',
                  title: 'Controlled Auto',
                  desc: 'Matches auto-approve only while your rule is enabled and the score meets your threshold. With no rule saved — or a disabled rule — every match goes to review.',
                  effect: 'Needs enabled rule',
                },
              ].map(({ mode, title, desc, effect }) => {
                const isCurrent = mode === applicationMode;
                return (
                  <div
                    key={mode}
                    className={`rounded-xl border p-4 text-left ${
                      isCurrent ? 'border-forest-300 bg-forest-50/60' : 'border-line bg-surface'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-bold text-ink">
                        {title}
                        {isCurrent && (
                          <span className="ml-1.5 text-[10px] font-semibold uppercase tracking-wide text-forest-700">
                            current
                          </span>
                        )}
                      </span>
                      <StatusPill
                        tone={mode === 'MANUAL' ? 'slate' : mode === 'ASSISTED' ? 'amber' : 'forest'}
                      >
                        {effect}
                      </StatusPill>
                    </div>
                    <p className="mt-1 text-[11px] leading-relaxed text-ink-muted">{desc}</p>
                  </div>
                );
              })}
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
              cannot be overridden by approval rules. If a rule cannot be read,
              every match falls back to human review and you are notified.
            </p>
          </div>

          <div className="space-y-2">
            <PrimaryButton
              type="submit"
              disabled={saving || !canSave}
              className="w-full py-3"
            >
              {saving ? 'Saving approval rules…' : 'Save approval rules'}
            </PrimaryButton>
            {!configured && !dirty && (
              <p className="text-center text-xs text-ink-muted">
                Adjust a setting to save a rule. Saving the untouched form would store a
                disabled rule.
              </p>
            )}
          </div>
        </form>
      </div>
    </PageShell>
  );
};
