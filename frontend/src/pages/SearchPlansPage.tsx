import React, { useCallback, useEffect, useState } from 'react';
import {
  createSearchPlan,
  deleteSearchPlan,
  expandSearchPlan,
  fetchSearchPlans,
  runSearchPlan,
  updateSearchPlan,
} from '../api/client';
import type { ExpandedQueryPlan, SearchPlan, SearchPlanExecutionSummary } from '../types';
import {
  Alert,
  Card,
  Chip,
  EmptyState,
  FilterPill,
  Label,
  Loading,
  PageHeader,
  PageShell,
  PrimaryButton,
  SecondaryButton,
  Select,
  StatusPill,
  TextInput,
} from '../components/ui';
import type { PillTone } from '../components/ui';

const SUPPORTED_SOURCES = [
  'ADZUNA',
  'ARBEITNOW',
  'FREEHIRE',
  'TSENTA',
  'GREENHOUSE',
  'LEVER',
  'ASHBY',
  'WORKDAY',
  'RIPPLING',
] as const;

const REMOTE_TYPES = ['REMOTE', 'HYBRID', 'ONSITE'] as const;
const SENIORITY_LEVELS = ['ENTRY', 'MID', 'SENIOR', 'LEAD', 'PRINCIPAL'] as const;

interface PlanFormData {
  id?: string;
  name: string;
  targetTitles: string;
  targetSources: string[];
  searchFrequency: 'HOURLY' | 'DAILY' | 'WEEKLY' | 'MANUAL';
  maxResultsPerRun: number;
  active: boolean;
  primaryKeywords: string;
  excludeKeywords: string;
  targetLocations: string;
  remoteTypes: string[];
  seniorityLevels: string[];
}

const DEFAULT_FORM: PlanFormData = {
  name: '',
  targetTitles: '',
  targetSources: ['ADZUNA', 'ARBEITNOW', 'GREENHOUSE', 'LEVER', 'ASHBY'],
  searchFrequency: 'DAILY',
  maxResultsPerRun: 50,
  active: true,
  primaryKeywords: '',
  excludeKeywords: '',
  targetLocations: 'London, UK, Remote',
  remoteTypes: ['REMOTE', 'HYBRID'],
  seniorityLevels: ['SENIOR', 'LEAD'],
};

function splitCommaList(val?: string | null): string[] {
  if (!val) return [];
  return val
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

function joinList(arr?: string[] | null): string {
  if (!arr || arr.length === 0) return '';
  return arr.join(', ');
}

function executionStatusTone(status?: string | null): PillTone {
  switch (status) {
    case 'SUCCESS':
      return 'emerald';
    case 'PARTIAL':
      return 'amber';
    case 'RUNNING':
      return 'amber';
    case 'FAILED':
      return 'red';
    case 'QUEUED':
    case 'ACCEPTED':
      return 'sky';
    case 'SKIPPED':
      return 'slate';
    default:
      return 'slate';
  }
}

export const SearchPlansPage: React.FC = () => {
  const [plans, setPlans] = useState<SearchPlan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionMessage, setActionMessage] = useState<{ tone: 'success' | 'error' | 'info'; text: string } | null>(null);

  // Form modal state (create or edit)
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingPlanId, setEditingPlanId] = useState<string | null>(null);
  const [formData, setFormData] = useState<PlanFormData>(DEFAULT_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Run Now state
  const [runningPlanId, setRunningPlanId] = useState<string | null>(null);
  const [executionSummary, setExecutionSummary] = useState<SearchPlanExecutionSummary | null>(null);

  // Preview Expansion modal state
  const [expansionPreview, setExpansionPreview] = useState<ExpandedQueryPlan | null>(null);
  const [expandingPlanId, setExpandingPlanId] = useState<string | null>(null);

  const loadPlans = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchSearchPlans();
      setPlans(data);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to load search plans.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadPlans();
  }, [loadPlans]);

  const handleOpenCreate = () => {
    setEditingPlanId(null);
    setFormData(DEFAULT_FORM);
    setFormError(null);
    setIsModalOpen(true);
  };

  const handleOpenEdit = (plan: SearchPlan) => {
    setEditingPlanId(plan.id);
    const facets = (plan.queryFacets || plan.query_facets || {}) as Record<string, any>;
    const titles = plan.targetTitles || plan.target_titles || [];
    const sources = plan.targetSources || plan.target_sources || [];

    setFormData({
      id: plan.id,
      name: plan.name,
      targetTitles: joinList(titles),
      targetSources: sources.length > 0 ? sources : [...SUPPORTED_SOURCES],
      searchFrequency: (plan.searchFrequency || plan.search_frequency || 'DAILY') as any,
      maxResultsPerRun: plan.maxResultsPerRun || plan.max_results_per_run || 50,
      active: plan.active !== false,
      primaryKeywords: joinList(facets.keywords || facets.primaryKeywords || []),
      excludeKeywords: joinList(facets.excludeKeywords || facets.exclude || facets.negativeKeywords || []),
      targetLocations: joinList(facets.locations || facets.targetLocations || []),
      remoteTypes: (facets.remoteTypes || []) as string[],
      seniorityLevels: (facets.seniorityLevels || facets.seniority || []) as string[],
    });
    setFormError(null);
    setIsModalOpen(true);
  };

  const handleToggleActive = async (plan: SearchPlan) => {
    const updatedActive = !plan.active;
    try {
      const updated = await updateSearchPlan(plan.id, {
        name: plan.name,
        targetTitles: plan.targetTitles || plan.target_titles || [],
        targetSources: plan.targetSources || plan.target_sources || [],
        searchFrequency: plan.searchFrequency || plan.search_frequency || 'DAILY',
        maxResultsPerRun: plan.maxResultsPerRun || plan.max_results_per_run || 50,
        active: updatedActive,
        queryFacets: plan.queryFacets || plan.query_facets || {},
      });
      setPlans((prev) => prev.map((p) => (p.id === plan.id ? updated : p)));
      setActionMessage({
        tone: 'success',
        text: `Plan "${plan.name}" is now ${updatedActive ? 'Active' : 'Paused'}.`,
      });
    } catch (err: unknown) {
      setActionMessage({
        tone: 'error',
        text: err instanceof Error ? err.message : 'Failed to update search plan status.',
      });
    }
  };

  const handleDelete = async (plan: SearchPlan) => {
    if (!window.confirm(`Are you sure you want to delete the search plan "${plan.name}"?`)) {
      return;
    }
    try {
      await deleteSearchPlan(plan.id);
      setPlans((prev) => prev.filter((p) => p.id !== plan.id));
      setActionMessage({
        tone: 'success',
        text: `Search plan "${plan.name}" deleted.`,
      });
    } catch (err: unknown) {
      setActionMessage({
        tone: 'error',
        text: err instanceof Error ? err.message : 'Failed to delete search plan.',
      });
    }
  };

  const handleRunNow = async (plan: SearchPlan) => {
    setRunningPlanId(plan.id);
    setActionMessage(null);
    setExecutionSummary(null);
    try {
      const summary = await runSearchPlan(plan.id);
      setExecutionSummary(summary);
      // Reload plans to refresh last_execution_status and timestamp
      const refreshed = await fetchSearchPlans();
      setPlans(refreshed);
    } catch (err: unknown) {
      setActionMessage({
        tone: 'error',
        text: err instanceof Error ? err.message : 'Failed to run search plan.',
      });
    } finally {
      setRunningPlanId(null);
    }
  };

  const handleExpandPreview = async (plan: SearchPlan) => {
    setExpandingPlanId(plan.id);
    try {
      const expanded = await expandSearchPlan(plan.id);
      setExpansionPreview(expanded);
    } catch (err: unknown) {
      setActionMessage({
        tone: 'error',
        text: err instanceof Error ? err.message : 'Failed to expand search plan queries.',
      });
    } finally {
      setExpandingPlanId(null);
    }
  };

  const handleSaveForm = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.name.trim()) {
      setFormError('Plan name is required.');
      return;
    }
    const titles = splitCommaList(formData.targetTitles);
    if (titles.length === 0) {
      setFormError('At least one target job title is required.');
      return;
    }
    if (formData.maxResultsPerRun < 1 || formData.maxResultsPerRun > 500) {
      setFormError('Maximum results per run must be between 1 and 500.');
      return;
    }

    const payload: Partial<SearchPlan> = {
      name: formData.name.trim(),
      targetTitles: titles,
      targetSources: formData.targetSources.length > 0 ? formData.targetSources : [...SUPPORTED_SOURCES],
      searchFrequency: formData.searchFrequency,
      maxResultsPerRun: Number(formData.maxResultsPerRun),
      active: formData.active,
      queryFacets: {
        keywords: splitCommaList(formData.primaryKeywords),
        excludeKeywords: splitCommaList(formData.excludeKeywords),
        exclude: splitCommaList(formData.excludeKeywords),
        locations: splitCommaList(formData.targetLocations),
        remoteTypes: formData.remoteTypes,
        seniorityLevels: formData.seniorityLevels,
        seniority: formData.seniorityLevels,
      },
    };

    setSaving(true);
    setFormError(null);
    try {
      if (editingPlanId) {
        const updated = await updateSearchPlan(editingPlanId, payload);
        setPlans((prev) => prev.map((p) => (p.id === editingPlanId ? updated : p)));
        setActionMessage({ tone: 'success', text: `Search plan "${updated.name}" updated successfully.` });
      } else {
        const created = await createSearchPlan(payload);
        setPlans((prev) => [created, ...prev]);
        setActionMessage({ tone: 'success', text: `Search plan "${created.name}" created successfully.` });
      }
      setIsModalOpen(false);
    } catch (err: unknown) {
      setFormError(err instanceof Error ? err.message : 'Failed to save search plan.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <PageShell>
      <PageHeader
        eyebrow="Discovery Intelligence"
        title="Search Plans"
        subtitle="Configure autonomous multi-source retrieval, role targets, query synonym expansion, and multi-stage reranking."
        actions={
          <PrimaryButton onClick={handleOpenCreate}>
            <svg viewBox="0 0 20 20" fill="none" className="h-4 w-4" aria-hidden="true">
              <path d="M10 4.5v11M4.5 10h11" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            <span>Create Search Plan</span>
          </PrimaryButton>
        }
      />

      {actionMessage && (
        <div className="mt-4">
          <Alert tone={actionMessage.tone}>{actionMessage.text}</Alert>
        </div>
      )}

      {/* Execution Summary Banner */}
      {executionSummary && (
        <div className="mt-4">
          <Card className="border-forest-300 bg-forest-50/50 p-5">
            <div className="flex items-start justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-[11px] font-bold uppercase tracking-wider text-forest-800">
                    Search Plan Execution Run
                  </span>
                  <StatusPill tone={executionStatusTone(executionSummary.status)}>
                    {executionSummary.status}
                  </StatusPill>
                </div>
                <h3 className="mt-1 text-base font-bold text-ink">
                  {executionSummary.planName}
                </h3>
              </div>
              <SecondaryButton
                className="text-xs"
                onClick={() => setExecutionSummary(null)}
              >
                Dismiss
              </SecondaryButton>
            </div>

            <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4 text-center">
              <div className="rounded-lg bg-surface p-3 border border-line">
                <div className="text-xs text-ink-muted">Sources Queried</div>
                <div className="text-lg font-bold text-ink">{executionSummary.sourcesQueried}</div>
                <div className="text-[11px] text-emerald-700">{executionSummary.sourcesSucceeded} OK · {executionSummary.sourcesFailed} Failed</div>
              </div>
              <div className="rounded-lg bg-surface p-3 border border-line">
                <div className="text-xs text-ink-muted">Jobs Ingested</div>
                <div className="text-lg font-bold text-forest-800">{executionSummary.jobsIngested}</div>
                <div className="text-[11px] text-ink-faint">deduplicated</div>
              </div>
              <div className="rounded-lg bg-surface p-3 border border-line">
                <div className="text-xs text-ink-muted">Jobs Matched & Reranked</div>
                <div className="text-lg font-bold text-forest-800">{executionSummary.jobsMatched}</div>
                <div className="text-[11px] text-ink-faint">5-stage scored</div>
              </div>
              <div className="rounded-lg bg-surface p-3 border border-line">
                <div className="text-xs text-ink-muted">Outcome</div>
                <div className="text-lg font-bold text-ink">{executionSummary.status}</div>
                <div className="text-[11px] text-ink-faint">{executionSummary.errors.length} error(s)</div>
              </div>
            </div>

            {executionSummary.errors.length > 0 && (
              <div className="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 text-xs text-red-800">
                <p className="font-semibold">Execution Warnings & Errors:</p>
                <ul className="mt-1 list-disc pl-4 space-y-0.5">
                  {executionSummary.errors.map((err, i) => (
                    <li key={i}>{err}</li>
                  ))}
                </ul>
              </div>
            )}

            {executionSummary.providerFacetSupport && Object.keys(executionSummary.providerFacetSupport).length > 0 && (
              <div className="mt-3 rounded-lg border border-line bg-surface p-3 text-xs text-ink-muted">
                <p className="font-semibold text-ink">Provider Capability & Facet Routing:</p>
                <div className="mt-1.5 grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                  {Object.entries(executionSummary.providerFacetSupport).map(([key, val]) => (
                    <div key={key} className="rounded bg-surface-raised p-2 border border-line/60">
                      <span className="font-semibold text-ink-soft">{key}: </span>
                      <span>{val}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {executionSummary.notes.length > 0 && (
              <div className="mt-3 rounded-lg border border-line bg-surface p-3 text-xs text-ink-muted">
                <p className="font-semibold text-ink">Provider Transparency Notes:</p>
                <ul className="mt-1 list-disc pl-4 space-y-0.5">
                  {executionSummary.notes.map((note, i) => (
                    <li key={i}>{note}</li>
                  ))}
                </ul>
              </div>
            )}
          </Card>
        </div>
      )}

      {loading ? (
        <div className="mt-8">
          <Loading>Loading search plans…</Loading>
        </div>
      ) : error ? (
        <div className="mt-6">
          <Alert tone="error">{error}</Alert>
          <div className="mt-4">
            <SecondaryButton onClick={loadPlans}>Retry</SecondaryButton>
          </div>
        </div>
      ) : plans.length === 0 ? (
        <div className="mt-8">
          <EmptyState
            title="No search plans configured"
            body="Create a search plan to automatically discover, query, and rerank jobs matching your target roles across Adzuna, Arbeitnow, Greenhouse, Lever, and other platforms."
            actions={
              <PrimaryButton onClick={handleOpenCreate}>
                Create your first search plan
              </PrimaryButton>
            }
          />
        </div>
      ) : (
        <div className="mt-6 space-y-4">
          {plans.map((plan) => {
            const titles = plan.targetTitles || plan.target_titles || [];
            const sources = plan.targetSources || plan.target_sources || [];
            const freq = plan.searchFrequency || plan.search_frequency || 'DAILY';
            const maxRes = plan.maxResultsPerRun || plan.max_results_per_run || 50;
            const lastStatus = plan.lastExecutionStatus || plan.last_execution_status;
            const lastError = plan.lastExecutionError || plan.last_execution_error;
            const lastResults = plan.lastExecutionResults ?? plan.last_execution_results ?? 0;
            const lastRun = plan.lastRunAt || plan.last_run_at;
            const facets = (plan.queryFacets || plan.query_facets || {}) as Record<string, any>;
            const isRunning = runningPlanId === plan.id;
            const isExpanding = expandingPlanId === plan.id;

            return (
              <Card key={plan.id} className="p-5">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="text-base font-bold text-ink">{plan.name}</h2>
                      <StatusPill tone={plan.active ? 'forest' : 'slate'}>
                        {plan.active ? 'Active' : 'Paused'}
                      </StatusPill>
                      <StatusPill tone="sky">{freq}</StatusPill>
                      <span className="text-xs text-ink-faint">
                        Up to {maxRes} jobs / run
                      </span>
                    </div>

                    {/* Target Titles */}
                    <div className="mt-2.5 flex flex-wrap items-center gap-1.5 text-xs">
                      <span className="font-semibold text-ink-soft">Target Titles:</span>
                      {titles.map((title, i) => (
                        <Chip key={i}>{title}</Chip>
                      ))}
                      {titles.length === 0 && <span className="text-ink-faint">None specified</span>}
                    </div>

                    {/* Sources */}
                    <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs">
                      <span className="font-semibold text-ink-soft">Sources:</span>
                      {sources.length > 0 ? (
                        sources.map((src, i) => <Chip key={i}>{src}</Chip>)
                      ) : (
                        <span className="text-ink-faint">All supported sources</span>
                      )}
                    </div>

                    {/* Query Facets Summary */}
                    <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-muted">
                      {facets.locations && facets.locations.length > 0 && (
                        <span>📍 {facets.locations.join(', ')}</span>
                      )}
                      {facets.remoteTypes && facets.remoteTypes.length > 0 && (
                        <span>🌐 {facets.remoteTypes.join(', ')}</span>
                      )}
                      {facets.seniorityLevels && facets.seniorityLevels.length > 0 && (
                        <span>🎖️ {facets.seniorityLevels.join(', ')}</span>
                      )}
                      {facets.keywords && facets.keywords.length > 0 && (
                        <span>🏷️ Keywords: {facets.keywords.join(', ')}</span>
                      )}
                      {facets.excludeKeywords && facets.excludeKeywords.length > 0 && (
                        <span className="text-red-700">⛔ Exclude: {facets.excludeKeywords.join(', ')}</span>
                      )}
                    </div>

                    {/* Execution Outcome */}
                    <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-line/60 pt-2.5 text-xs text-ink-muted">
                      <span className="font-medium text-ink-soft">Last Execution:</span>
                      {lastStatus ? (
                        <StatusPill tone={executionStatusTone(lastStatus)}>
                          {lastStatus}
                        </StatusPill>
                      ) : (
                        <StatusPill tone="slate">Not run yet</StatusPill>
                      )}
                      {lastRun ? (
                        <span>{new Date(lastRun).toLocaleString()}</span>
                      ) : (
                        <span>Never</span>
                      )}
                      {lastResults > 0 && (
                        <span className="font-semibold text-forest-700">({lastResults} results)</span>
                      )}
                      {lastError && (
                        <span className="text-red-700 font-medium" title={lastError}>
                          ⚠️ {lastError.length > 60 ? `${lastError.slice(0, 60)}…` : lastError}
                        </span>
                      )}
                    </div>
                  </div>

                  {/* Right side actions */}
                  <div className="flex shrink-0 flex-wrap items-center gap-2 self-start">
                    <SecondaryButton
                      onClick={() => handleRunNow(plan)}
                      disabled={isRunning}
                      className="text-xs"
                    >
                      {isRunning ? (
                        <>
                          <span className="h-3 w-3 animate-spin rounded-full border border-forest-600 border-t-transparent" />
                          <span>Running…</span>
                        </>
                      ) : (
                        <>
                          <svg viewBox="0 0 20 20" fill="none" className="h-3.5 w-3.5 text-forest-700" aria-hidden="true">
                            <path d="M5.5 4.5l10 5.5-10 5.5V4.5z" fill="currentColor" />
                          </svg>
                          <span>Run Now</span>
                        </>
                      )}
                    </SecondaryButton>

                    <SecondaryButton
                      onClick={() => handleExpandPreview(plan)}
                      disabled={isExpanding}
                      className="text-xs"
                    >
                      Preview Expansion
                    </SecondaryButton>

                    <SecondaryButton
                      onClick={() => handleToggleActive(plan)}
                      className="text-xs"
                    >
                      {plan.active ? 'Pause' : 'Activate'}
                    </SecondaryButton>

                    <SecondaryButton
                      onClick={() => handleOpenEdit(plan)}
                      className="text-xs"
                    >
                      Edit
                    </SecondaryButton>

                    <button
                      type="button"
                      onClick={() => handleDelete(plan)}
                      className="rounded-lg p-2 text-ink-faint transition-colors hover:bg-red-50 hover:text-red-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-600"
                      title="Delete search plan"
                      aria-label="Delete search plan"
                    >
                      <svg viewBox="0 0 20 20" fill="none" className="h-4 w-4" aria-hidden="true">
                        <path d="M4 6h12M7 6V4a1 1 0 011-1h4a1 1 0 011 1v2m2 0v10a2 2 0 01-2 2H7a2 2 0 01-2-2V6h10z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </button>
                  </div>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {/* CREATE / EDIT MODAL */}
      {isModalOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="plan-modal-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6"
        >
          <div
            className="fixed inset-0 bg-ink-950/60 backdrop-blur-sm transition-opacity"
            onClick={() => !saving && setIsModalOpen(false)}
            aria-hidden="true"
          />

          <div className="relative flex max-h-[90vh] w-full max-w-2xl flex-col rounded-2xl border border-line bg-surface shadow-2xl overflow-hidden z-10">
            {/* Modal Header */}
            <div className="flex items-center justify-between border-b border-line px-6 py-4 bg-surface-raised">
              <div>
                <span className="text-[11px] font-bold uppercase tracking-wider text-forest-700">
                  {editingPlanId ? 'Edit Configuration' : 'New Configuration'}
                </span>
                <h2 id="plan-modal-title" className="mt-0.5 text-lg font-bold text-ink">
                  {editingPlanId ? 'Edit Search Plan' : 'Create Search Plan'}
                </h2>
              </div>
              <button
                type="button"
                onClick={() => setIsModalOpen(false)}
                className="rounded-lg p-1 text-ink-faint hover:bg-cream-100 hover:text-ink focus:outline-none"
                aria-label="Close modal"
              >
                <svg viewBox="0 0 20 20" fill="none" className="h-5 w-5" aria-hidden="true">
                  <path d="M5 5l10 10M15 5L5 15" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
                </svg>
              </button>
            </div>

            {/* Modal Form */}
            <form onSubmit={handleSaveForm} className="flex-1 overflow-y-auto px-6 py-5 space-y-4">
              {formError && <Alert tone="error">{formError}</Alert>}

              {/* Name */}
              <div>
                <Label htmlFor="plan-name">Plan Name *</Label>
                <TextInput
                  id="plan-name"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  placeholder="e.g. Senior Backend Engineering UK"
                  required
                />
              </div>

              {/* Target Titles */}
              <div>
                <Label htmlFor="plan-titles">Target Roles & Titles * (comma-separated)</Label>
                <TextInput
                  id="plan-titles"
                  value={formData.targetTitles}
                  onChange={(e) => setFormData({ ...formData, targetTitles: e.target.value })}
                  placeholder="e.g. Senior Java Engineer, Backend Tech Lead, Staff Software Engineer"
                  required
                />
                <p className="mt-1 text-[11px] text-ink-muted">
                  These titles will automatically be expanded with synonyms and variant job titles during discovery.
                </p>
              </div>

              {/* Sources */}
              <div>
                <Label>Target Sources</Label>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {SUPPORTED_SOURCES.map((src) => {
                    const isSelected = formData.targetSources.includes(src);
                    return (
                      <FilterPill
                        key={src}
                        selected={isSelected}
                        onClick={() => {
                          const updated = isSelected
                            ? formData.targetSources.filter((s) => s !== src)
                            : [...formData.targetSources, src];
                          setFormData({ ...formData, targetSources: updated });
                        }}
                      >
                        {src}
                      </FilterPill>
                    );
                  })}
                </div>
                <div className="mt-1 flex gap-2 text-xs">
                  <button
                    type="button"
                    onClick={() => setFormData({ ...formData, targetSources: [...SUPPORTED_SOURCES] })}
                    className="text-forest-700 underline"
                  >
                    Select All
                  </button>
                  <button
                    type="button"
                    onClick={() => setFormData({ ...formData, targetSources: [] })}
                    className="text-ink-faint underline"
                  >
                    Clear All
                  </button>
                </div>
              </div>

              {/* Frequency & Max Results */}
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <div>
                  <Label htmlFor="plan-freq">Search Frequency</Label>
                  <Select
                    id="plan-freq"
                    value={formData.searchFrequency}
                    onChange={(e) =>
                      setFormData({
                        ...formData,
                        searchFrequency: e.target.value as any,
                      })
                    }
                  >
                    <option value="HOURLY">Hourly (every hour)</option>
                    <option value="DAILY">Daily (every 24 hours)</option>
                    <option value="WEEKLY">Weekly (every 7 days)</option>
                    <option value="MANUAL">Manual Only</option>
                  </Select>
                </div>
                <div>
                  <Label htmlFor="plan-max">Max Results Per Run</Label>
                  <TextInput
                    id="plan-max"
                    type="number"
                    min="1"
                    max="500"
                    value={formData.maxResultsPerRun}
                    onChange={(e) =>
                      setFormData({
                        ...formData,
                        maxResultsPerRun: parseInt(e.target.value, 10) || 50,
                      })
                    }
                  />
                </div>
              </div>

              {/* Active Toggle */}
              <div className="flex items-center gap-2 pt-1">
                <input
                  type="checkbox"
                  id="plan-active"
                  checked={formData.active}
                  onChange={(e) => setFormData({ ...formData, active: e.target.checked })}
                  className="h-4 w-4 rounded border-line text-forest-600 focus:ring-forest-600"
                />
                <label htmlFor="plan-active" className="text-sm font-semibold text-ink">
                  Enable active automated scheduling
                </label>
              </div>

              {/* Query Facets Section */}
              <div className="border-t border-line pt-3 space-y-3">
                <h3 className="text-xs font-bold uppercase tracking-wider text-forest-700">
                  Search & Reranking Facets
                </h3>

                <div>
                  <Label htmlFor="plan-keywords">Primary Keywords (comma-separated)</Label>
                  <TextInput
                    id="plan-keywords"
                    value={formData.primaryKeywords}
                    onChange={(e) => setFormData({ ...formData, primaryKeywords: e.target.value })}
                    placeholder="e.g. Java, Spring Boot, PostgreSQL, Kubernetes"
                  />
                </div>

                <div>
                  <Label htmlFor="plan-exclude">Exclude / Negative Keywords (comma-separated)</Label>
                  <TextInput
                    id="plan-exclude"
                    value={formData.excludeKeywords}
                    onChange={(e) => setFormData({ ...formData, excludeKeywords: e.target.value })}
                    placeholder="e.g. PHP, WordPress, .NET, Unpaid"
                  />
                  <p className="mt-0.5 text-[11px] text-ink-muted">
                    Postings containing these negative keywords will be filtered out downstream during reranking.
                  </p>
                </div>

                <div>
                  <Label htmlFor="plan-locations">Target Locations (comma-separated)</Label>
                  <TextInput
                    id="plan-locations"
                    value={formData.targetLocations}
                    onChange={(e) => setFormData({ ...formData, targetLocations: e.target.value })}
                    placeholder="e.g. London, UK, Remote, Bristol"
                  />
                </div>

                <div>
                  <Label>Remote Types</Label>
                  <div className="mt-1 flex flex-wrap gap-1.5">
                    {REMOTE_TYPES.map((type) => {
                      const isSelected = formData.remoteTypes.includes(type);
                      return (
                        <FilterPill
                          key={type}
                          selected={isSelected}
                          onClick={() => {
                            const updated = isSelected
                              ? formData.remoteTypes.filter((t) => t !== type)
                              : [...formData.remoteTypes, type];
                            setFormData({ ...formData, remoteTypes: updated });
                          }}
                        >
                          {type}
                        </FilterPill>
                      );
                    })}
                  </div>
                </div>

                <div>
                  <Label>Seniority Levels</Label>
                  <div className="mt-1 flex flex-wrap gap-1.5">
                    {SENIORITY_LEVELS.map((lvl) => {
                      const isSelected = formData.seniorityLevels.includes(lvl);
                      return (
                        <FilterPill
                          key={lvl}
                          selected={isSelected}
                          onClick={() => {
                            const updated = isSelected
                              ? formData.seniorityLevels.filter((l) => l !== lvl)
                              : [...formData.seniorityLevels, lvl];
                            setFormData({ ...formData, seniorityLevels: updated });
                          }}
                        >
                          {lvl}
                        </FilterPill>
                      );
                    })}
                  </div>
                </div>
              </div>

              {/* Form Buttons */}
              <div className="flex items-center justify-end gap-2 border-t border-line pt-4">
                <SecondaryButton
                  type="button"
                  onClick={() => setIsModalOpen(false)}
                  disabled={saving}
                >
                  Cancel
                </SecondaryButton>
                <PrimaryButton type="submit" disabled={saving}>
                  {saving ? 'Saving…' : editingPlanId ? 'Save Changes' : 'Create Plan'}
                </PrimaryButton>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* PREVIEW EXPANSION MODAL */}
      {expansionPreview && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="preview-modal-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6"
        >
          <div
            className="fixed inset-0 bg-ink-950/60 backdrop-blur-sm transition-opacity"
            onClick={() => setExpansionPreview(null)}
            aria-hidden="true"
          />

          <div className="relative flex max-h-[85vh] w-full max-w-xl flex-col rounded-2xl border border-line bg-surface shadow-2xl overflow-hidden z-10">
            <div className="flex items-center justify-between border-b border-line px-6 py-4 bg-surface-raised">
              <div>
                <span className="text-[11px] font-bold uppercase tracking-wider text-forest-700">
                  Query Expander Analysis
                </span>
                <h2 id="preview-modal-title" className="mt-0.5 text-lg font-bold text-ink">
                  {expansionPreview.planName}
                </h2>
              </div>
              <button
                type="button"
                onClick={() => setExpansionPreview(null)}
                className="rounded-lg p-1 text-ink-faint hover:bg-cream-100 hover:text-ink focus:outline-none"
                aria-label="Close modal"
              >
                <svg viewBox="0 0 20 20" fill="none" className="h-5 w-5" aria-hidden="true">
                  <path d="M5 5l10 10M15 5L5 15" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
                </svg>
              </button>
            </div>

            <div className="flex-1 overflow-y-auto px-6 py-5 space-y-4 text-xs">
              <div>
                <p className="font-semibold text-ink-soft">Configured Titles:</p>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {expansionPreview.originalTitles.map((t, i) => (
                    <Chip key={i}>{t}</Chip>
                  ))}
                </div>
              </div>

              <div>
                <p className="font-semibold text-forest-800">Generated Title Synonyms ({expansionPreview.expandedTitles.length}):</p>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {expansionPreview.expandedTitles.map((t, i) => (
                    <span
                      key={i}
                      className="rounded-md border border-forest-200 bg-forest-50 px-2 py-0.5 font-medium text-forest-800"
                    >
                      {t}
                    </span>
                  ))}
                </div>
              </div>

              {expansionPreview.primaryKeywords.length > 0 && (
                <div>
                  <p className="font-semibold text-ink-soft">Primary Keywords:</p>
                  <p className="mt-1 text-ink-muted">{expansionPreview.primaryKeywords.join(', ')}</p>
                </div>
              )}

              {expansionPreview.negativeKeywords.length > 0 && (
                <div>
                  <p className="font-semibold text-red-700">Negative / Exclude Keywords:</p>
                  <p className="mt-1 text-red-600">{expansionPreview.negativeKeywords.join(', ')}</p>
                </div>
              )}

              {expansionPreview.targetLocations.length > 0 && (
                <div>
                  <p className="font-semibold text-ink-soft">Target Locations:</p>
                  <p className="mt-1 text-ink-muted">{expansionPreview.targetLocations.join(', ')}</p>
                </div>
              )}

              <div>
                <p className="font-semibold text-ink-soft">Target Sources:</p>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {expansionPreview.targetSources.map((s, i) => (
                    <Chip key={i}>{s}</Chip>
                  ))}
                </div>
              </div>

              <div className="border-t border-line pt-3 flex justify-between items-center text-ink-muted">
                <span>Result Limit: <strong>{expansionPreview.maxResults}</strong> jobs</span>
                <SecondaryButton onClick={() => setExpansionPreview(null)}>
                  Close
                </SecondaryButton>
              </div>
            </div>
          </div>
        </div>
      )}
    </PageShell>
  );
};
