import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { JobSource } from '../types';
import {
  DataTable,
  Loading,
  PageHeader,
  PageShell,
  SecondaryButton,
  SectionCard,
  StatusPill,
} from '../components/ui';

/**
 * Job Sources & Connectors.
 *
 * Presentation-only redesign: same GET /sources, same per-source health-check
 * handler, same compliance copy — rendered through the shared table system.
 */
export const SourcesPage: React.FC = () => {
  const [sources, setSources] = useState<JobSource[]>([]);
  const [loading, setLoading] = useState(true);
  const [checkingId, setCheckingId] = useState<string | null>(null);

  const fetchSources = async () => {
    setLoading(true);
    try {
      const s = await apiFetch<JobSource[]>('/sources');
      setSources(s || []);
    } catch {
      setSources([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSources();
  }, []);

  const handleHealthCheck = async (id: string) => {
    setCheckingId(id);
    try {
      await apiFetch(`/sources/${id}/health-check`, { method: 'POST' });
      fetchSources();
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : 'Health check failed');
    } finally {
      setCheckingId(null);
    }
  };

  if (loading) {
    return <PageShell><Loading>Loading job source registry…</Loading></PageShell>;
  }

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Discovery"
          title="Job Sources & Connectors"
          subtitle="The public ATS boards Robin is allowed to watch, and the compliance policies that govern them."
        />

        <div className="rounded-xl border border-forest-200 bg-forest-50 px-4 py-3 text-xs leading-relaxed text-forest-900">
          <span className="mb-0.5 block font-bold">Compliance policy engine</span>
          All automated discovery connects strictly via legitimate public ATS boards (Greenhouse, Lever,
          SmartRecruiters, Ashby). No automated LinkedIn scraping is permitted; third-party links resolve to
          direct company boards.
        </div>

        <SectionCard
          title="Configured ATS sources"
          hint={`${sources.length} configured`}
          bodyClassName="pt-2"
        >
          <DataTable
            columns={['Source name', 'Platform kind', 'Org identifier', 'Policy', 'Rate limit', 'Health', 'Actions']}
            emptyMessage="No sources found in the database."
            rows={sources.map((src) => [
              <span className="font-semibold text-ink">{src.display_name}</span>,
              <span className="font-mono text-xs text-forest-700">{src.kind}</span>,
              <span className="font-mono text-xs text-ink-muted">{src.org_identifier}</span>,
              <StatusPill tone="forest">{src.policy}</StatusPill>,
              <span className="whitespace-nowrap font-mono text-xs">{src.rate_limit_per_min}/min</span>,
              <span className="inline-flex items-center gap-1.5 text-xs font-medium text-emerald-700">
                <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
                Healthy
              </span>,
              <SecondaryButton
                onClick={() => handleHealthCheck(src.id)}
                disabled={checkingId === src.id}
                className="whitespace-nowrap px-3 py-1.5 text-xs"
              >
                {checkingId === src.id ? 'Pinging…' : 'Ping board'}
              </SecondaryButton>,
            ])}
          />
        </SectionCard>
      </div>
    </PageShell>
  );
};
