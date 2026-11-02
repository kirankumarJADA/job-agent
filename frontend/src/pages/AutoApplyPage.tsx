import React, { useCallback, useEffect, useState } from 'react';
import { ApiError, apiFetch } from '../api/client';
import type {
  AutoApplyPreview,
  AutoApplyPreviewItem,
  AutoApplyRunDetail,
  AutoApplyRunSummary,
  AutoApplySettings,
} from '../types';
import {
  Alert,
  Label,
  Loading,
  PageHeader,
  PageShell,
  PrimaryButton,
  SecondaryButton,
  SectionCard,
  Select,
  StatusPill,
  TextInput,
} from '../components/ui';

/**
 * Auto Apply (Phase 5, Robin x Tsenta continuation) — the explicit, bounded
 * batch preparation workflow.
 *
 * Deliberately separate from the Approval Rules / Auto Apply Activity pages:
 * those configure and report on the CONTINUOUS background decision engine
 * (every newly matched job, all the time). This page configures and drives a
 * SEPARATE, user-triggered sweep over the candidate's existing matched-job
 * backlog — enabling it here never starts anything; starting a run is always
 * an explicit button press, bounded by the settings below.
 *
 * "Prepared" always means: an application was created and handed to the
 * existing asynchronous CV/cover-letter/answer preparation pipeline. It is
 * never evidence of submission — every prepared application still needs your
 * review on the Review Queue / Applications pages before anything is sent
 * anywhere, and REAL_SUBMIT stays hard-stopped regardless of these settings.
 */

const OUTCOME_LABELS: Record<string, string> = {
  PREPARED: 'Prepared',
  WOULD_PREPARE: 'Would prepare',
  SKIPPED_DUPLICATE: 'Skipped — duplicate',
  SKIPPED_THRESHOLD: 'Skipped — below threshold',
  SKIPPED_MISSING_PROFILE: 'Skipped — profile incomplete',
  SKIPPED_UNSUPPORTED_FLOW: 'Skipped — unsupported flow',
  QUOTA_LIMITED: 'Skipped — daily cap reached',
  FAILED: 'Failed',
};

function outcomeLabel(key: string): string {
  return OUTCOME_LABELS[key] ?? key;
}

function outcomeTone(key: string): 'emerald' | 'amber' | 'red' | 'slate' {
  if (key === 'PREPARED' || key === 'WOULD_PREPARE') return 'emerald';
  if (key === 'FAILED') return 'red';
  if (key === 'QUOTA_LIMITED' || key === 'SKIPPED_MISSING_PROFILE') return 'amber';
  return 'slate';
}

export const AutoApplyPage: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [settings, setSettings] = useState<AutoApplySettings | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveMessage, setSaveMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const [preview, setPreview] = useState<AutoApplyPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  const [history, setHistory] = useState<AutoApplyRunSummary[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  const [running, setRunning] = useState(false);
  const [runMessage, setRunMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [selectedRun, setSelectedRun] = useState<AutoApplyRunDetail | null>(null);
  const [selectedRunLoading, setSelectedRunLoading] = useState(false);

  const minScoreValid = settings != null && Number.isInteger(settings.minScore) && settings.minScore >= 1 && settings.minScore <= 100;
  const dailyCapValid = settings != null && Number.isInteger(settings.dailyCap) && settings.dailyCap >= 1 && settings.dailyCap <= 50;
  const maxBatchValid = settings != null && Number.isInteger(settings.maxBatchSize) && settings.maxBatchSize >= 1 && settings.maxBatchSize <= 50;
  const canSave = settings != null && minScoreValid && dailyCapValid && maxBatchValid && (settings.configured || dirty);

  const loadSettings = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const result = await apiFetch<AutoApplySettings>('/auto-apply/settings');
      setSettings(result);
      setDirty(false);
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : 'Could not load Auto Apply settings.');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadHistory = useCallback(async () => {
    setHistoryLoading(true);
    try {
      const result = await apiFetch<{ items: AutoApplyRunSummary[] }>('/auto-apply/runs?limit=10');
      setHistory(result.items);
    } catch {
      // Run history is secondary to settings; a failure here does not block the page.
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadSettings();
    void loadHistory();
  }, [loadSettings, loadHistory]);

  const update = (patch: Partial<AutoApplySettings>) => {
    setSettings((current) => (current ? { ...current, ...patch } : current));
    setDirty(true);
    setPreview(null);
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!settings || !canSave) return;
    setSaving(true);
    setSaveMessage(null);
    try {
      const saved = await apiFetch<AutoApplySettings>('/auto-apply/settings', {
        method: 'PUT',
        body: JSON.stringify({
          enabled: settings.enabled,
          minScore: settings.minScore,
          dailyCap: settings.dailyCap,
          maxBatchSize: settings.maxBatchSize,
          remoteType: settings.remoteType,
          location: settings.location,
          employmentType: settings.employmentType,
          salaryMin: settings.salaryMin,
          clearFilters: !settings.remoteType && !settings.location && !settings.employmentType && settings.salaryMin == null,
        }),
      });
      setSettings(saved);
      setDirty(false);
      setSaveMessage({ type: 'success', text: 'Auto Apply settings saved.' });
    } catch (err: unknown) {
      setSaveMessage({ type: 'error', text: err instanceof Error ? err.message : 'Save failed.' });
    } finally {
      setSaving(false);
    }
  };

  const handlePreview = async () => {
    setPreviewLoading(true);
    setPreviewError(null);
    try {
      const result = await apiFetch<AutoApplyPreview>('/auto-apply/preview');
      setPreview(result);
    } catch (err: unknown) {
      setPreviewError(err instanceof Error ? err.message : 'Could not load a preview.');
    } finally {
      setPreviewLoading(false);
    }
  };

  const handleStartRun = async () => {
    setRunning(true);
    setRunMessage(null);
    try {
      const run = await apiFetch<AutoApplyRunSummary>('/auto-apply/runs', { method: 'POST' });
      setRunMessage({
        type: 'success',
        text: `Run complete: ${run.prepared} prepared, ${run.jobsEligible} eligible of ${run.jobsConsidered} considered. ` +
          'Prepared applications still need your review before anything is submitted.',
      });
      setPreview(null);
      void loadHistory();
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setRunMessage({ type: 'error', text: err.message });
      } else {
        setRunMessage({ type: 'error', text: err instanceof Error ? err.message : 'The run could not be started.' });
      }
    } finally {
      setRunning(false);
    }
  };

  const openRun = async (id: string) => {
    setSelectedRunLoading(true);
    try {
      const detail = await apiFetch<AutoApplyRunDetail>(`/auto-apply/runs/${id}`);
      setSelectedRun(detail);
    } catch {
      setSelectedRun(null);
    } finally {
      setSelectedRunLoading(false);
    }
  };

  if (loading) {
    return (
      <PageShell>
        <Loading>Loading Auto Apply settings…</Loading>
      </PageShell>
    );
  }

  const refreshButton = (
    <SecondaryButton type="button" onClick={() => void loadSettings()} disabled={loading}>
      Refresh
    </SecondaryButton>
  );

  if (loadError || !settings) {
    return (
      <PageShell>
        <div className="space-y-6 max-w-4xl">
          <PageHeader eyebrow="Batch preparation" title="Auto Apply" subtitle="Prepare several matched applications at once, within limits you control." actions={refreshButton} />
          <Alert tone="error">{loadError ?? 'Could not load Auto Apply settings.'}</Alert>
        </div>
      </PageShell>
    );
  }

  return (
    <PageShell>
      <div className="space-y-6 max-w-4xl">
        <PageHeader
          eyebrow="Batch preparation"
          title="Auto Apply"
          subtitle="Explicitly sweep your matched-job backlog and prepare the eligible ones, bounded by the limits below. This prepares applications — it never submits one, and every prepared application still needs your review."
          actions={refreshButton}
        />

        {saveMessage && <Alert tone={saveMessage.type === 'success' ? 'success' : 'error'}>{saveMessage.text}</Alert>}

        <form onSubmit={handleSave} className="space-y-6">
          <SectionCard
            title="Batch preparation"
            actions={<StatusPill tone={settings.enabled ? 'emerald' : 'slate'}>{settings.enabled ? 'Enabled' : 'Disabled'}</StatusPill>}
            bodyClassName="space-y-4"
          >
            <div className="flex items-center justify-between gap-4">
              <div className="min-w-0">
                <p className="text-sm font-medium text-ink">Allow starting a bounded Auto Apply run</p>
                <p className="mt-0.5 text-xs text-ink-muted">
                  Must be enabled before a run can start. Enabling this never starts anything by
                  itself — a run only happens when you press &ldquo;Start run now&rdquo; below.
                </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={settings.enabled}
                aria-label="Toggle Auto Apply batch preparation"
                onClick={() => update({ enabled: !settings.enabled })}
                className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 focus-visible:ring-offset-2 ${
                  settings.enabled ? 'bg-forest-700' : 'bg-ink-faint/30'
                }`}
              >
                <span
                  aria-hidden="true"
                  className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow-lg transition-transform ${
                    settings.enabled ? 'translate-x-5' : 'translate-x-0.5'
                  }`}
                />
              </button>
            </div>
          </SectionCard>

          <SectionCard title="Eligibility and limits" bodyClassName="space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <div>
                <Label htmlFor="min-score">Minimum match score</Label>
                <TextInput
                  id="min-score"
                  type="number"
                  min={1}
                  max={100}
                  value={settings.minScore}
                  onChange={(e) => update({ minScore: Number(e.target.value) })}
                />
                {!minScoreValid && <p className="mt-1 text-xs text-red-700">Must be 1–100.</p>}
              </div>
              <div>
                <Label htmlFor="daily-cap">Max applications prepared per day</Label>
                <TextInput
                  id="daily-cap"
                  type="number"
                  min={1}
                  max={50}
                  value={settings.dailyCap}
                  onChange={(e) => update({ dailyCap: Number(e.target.value) })}
                />
                {!dailyCapValid && <p className="mt-1 text-xs text-red-700">Must be 1–50.</p>}
              </div>
              <div>
                <Label htmlFor="max-batch-size">Max jobs considered per run</Label>
                <TextInput
                  id="max-batch-size"
                  type="number"
                  min={1}
                  max={50}
                  value={settings.maxBatchSize}
                  onChange={(e) => update({ maxBatchSize: Number(e.target.value) })}
                />
                {!maxBatchValid && <p className="mt-1 text-xs text-red-700">Must be 1–50.</p>}
              </div>
            </div>
            <p className="text-xs text-ink-muted">
              This daily cap is this batch feature&rsquo;s own limit, separate from your overall
              application quota shown on{' '}
              <a href="/auto-apply-activity" className="font-medium text-forest-700 underline hover:text-forest-900">
                Auto Apply Activity
              </a>
              .
            </p>
          </SectionCard>

          <SectionCard title="Optional job filters" bodyClassName="space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <Label htmlFor="remote-type">Workplace type</Label>
                <Select
                  id="remote-type"
                  value={settings.remoteType ?? ''}
                  onChange={(e) => update({ remoteType: e.target.value || null })}
                >
                  <option value="">Any</option>
                  <option value="REMOTE">Remote</option>
                  <option value="HYBRID">Hybrid</option>
                  <option value="ONSITE">Onsite</option>
                </Select>
              </div>
              <div>
                <Label htmlFor="location-filter">Location contains</Label>
                <TextInput
                  id="location-filter"
                  value={settings.location ?? ''}
                  onChange={(e) => update({ location: e.target.value || null })}
                  placeholder="e.g. London"
                />
              </div>
            </div>
          </SectionCard>

          <div className="rounded-lg border border-forest-200 bg-forest-50 px-5 py-4 text-xs leading-relaxed text-forest-900">
            <p className="font-semibold">Safety guarantees</p>
            <p className="mt-1">
              A run only uses your already-scored matches — nothing is re-scored here. Duplicate
              and previously attempted applications are always excluded. Every prepared
              application still goes through the same human review, approval, and REAL_SUBMIT
              hard-stop as any other application in Robin.
            </p>
          </div>

          <PrimaryButton type="submit" disabled={saving || !canSave} className="w-full py-3">
            {saving ? 'Saving…' : 'Save Auto Apply settings'}
          </PrimaryButton>
        </form>

        <SectionCard
          title="Preview and run"
          actions={
            <div className="flex gap-2">
              <SecondaryButton type="button" onClick={() => void handlePreview()} disabled={previewLoading}>
                {previewLoading ? 'Loading preview…' : 'Preview eligible jobs'}
              </SecondaryButton>
              <PrimaryButton type="button" onClick={() => void handleStartRun()} disabled={running || !settings.enabled}>
                {running ? 'Running…' : 'Start run now'}
              </PrimaryButton>
            </div>
          }
          bodyClassName="space-y-3"
        >
          {!settings.enabled && (
            <p className="text-xs text-ink-muted">Enable and save batch preparation above before starting a run.</p>
          )}
          {runMessage && <Alert tone={runMessage.type === 'success' ? 'success' : 'error'}>{runMessage.text}</Alert>}
          {previewError && <Alert tone="error">{previewError}</Alert>}
          {preview && (
            <div>
              <p className="text-sm text-ink-muted">
                {preview.jobsConsidered} jobs considered, {preview.jobsEligible} at or above your
                threshold.
              </p>
              <ul className="mt-2 divide-y divide-line">
                {preview.items.slice(0, 20).map((item: AutoApplyPreviewItem) => (
                  <li key={item.jobId} className="flex items-center justify-between gap-3 py-2 text-sm">
                    <span className="min-w-0 truncate text-ink">{item.jobTitle} — {item.companyName}</span>
                    <span className="flex items-center gap-2 shrink-0">
                      {item.matchScore != null && <span className="font-mono text-xs text-ink-muted">{item.matchScore}</span>}
                      <StatusPill tone={outcomeTone(item.predictedOutcome)}>{outcomeLabel(item.predictedOutcome)}</StatusPill>
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </SectionCard>

        <SectionCard title="Run history" actions={historyLoading ? <StatusPill tone="slate">Loading…</StatusPill> : null} bodyClassName="space-y-3">
          {history.length === 0 ? (
            <p className="text-sm text-ink-muted">No Auto Apply runs yet.</p>
          ) : (
            <ul className="divide-y divide-line">
              {history.map((run) => (
                <li key={run.id} className="py-3">
                  <button
                    type="button"
                    onClick={() => void openRun(run.id)}
                    className="flex w-full items-center justify-between gap-3 text-left text-sm hover:bg-surface-sunken rounded px-2 -mx-2 py-1"
                  >
                    <span className="min-w-0">
                      <span className="font-medium text-ink">{new Date(run.startedAt).toLocaleString()}</span>
                      <span className="ml-2 text-ink-muted">
                        {run.prepared} prepared · {run.jobsConsidered} considered
                      </span>
                    </span>
                    <StatusPill tone={run.status === 'COMPLETED' ? 'emerald' : run.status === 'FAILED' ? 'red' : 'slate'}>
                      {run.status}
                    </StatusPill>
                  </button>
                  {selectedRun && selectedRun.id === run.id && (
                    <div className="mt-3 rounded-lg border border-line bg-surface-sunken p-3">
                      {selectedRunLoading ? (
                        <Loading>Loading run detail…</Loading>
                      ) : (
                        <>
                          <div className="grid grid-cols-2 gap-2 text-xs text-ink-muted sm:grid-cols-4">
                            <span>Prepared: {selectedRun.prepared}</span>
                            <span>Duplicate: {selectedRun.skippedDuplicate}</span>
                            <span>Below threshold: {selectedRun.skippedThreshold}</span>
                            <span>Cap reached: {selectedRun.quotaLimited}</span>
                            <span>Missing profile: {selectedRun.skippedMissingProfile}</span>
                            <span>Unsupported flow: {selectedRun.skippedUnsupportedFlow}</span>
                            <span>Failures: {selectedRun.failures}</span>
                          </div>
                          <ul className="mt-2 divide-y divide-line">
                            {selectedRun.items.map((item) => (
                              <li key={item.jobId} className="flex items-center justify-between gap-3 py-1.5 text-xs">
                                <span className="min-w-0 truncate text-ink">{item.jobTitle} — {item.companyName}</span>
                                <StatusPill tone={outcomeTone(item.outcome)}>{outcomeLabel(item.outcome)}</StatusPill>
                              </li>
                            ))}
                          </ul>
                          <p className="mt-2 text-xs">
                            <a href="/review-queue" className="font-medium text-forest-700 underline hover:text-forest-900">
                              Go to Review Queue
                            </a>
                            {' · '}
                            <a href="/applications" className="font-medium text-forest-700 underline hover:text-forest-900">
                              Go to Applications
                            </a>
                          </p>
                        </>
                      )}
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      </div>
    </PageShell>
  );
};
