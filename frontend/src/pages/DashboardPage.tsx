import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { ApplicationSummary, Job } from '../types';
import {
  Alert,
  EmptyState,
  JobStatusPill,
  Loading,
  PageHeader,
  PageShell,
  SecondaryButton,
  WorkplacePill,
} from '../components/ui';

interface ReviewQueueSummary {
  items?: Array<{ decision?: string }>;
  pendingCount?: number;
}

interface WorkflowStageCardProps {
  step: string;
  title: string;
  description: string;
  value: string;
  detail: string;
  href: string;
  action: string;
  accent: string;
}

const WorkflowStageCard: React.FC<WorkflowStageCardProps> = ({
  step, title, description, value, detail, href, action, accent,
}) => (
  <Link
    to={href}
    className="group block rounded-xl border border-line bg-surface p-5 shadow-card transition-all hover:-translate-y-0.5 hover:border-forest-300 hover:shadow-raise focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
  >
    <article>
      <div className="flex items-center justify-between gap-3">
        <span className="inline-flex h-9 w-9 items-center justify-center rounded-lg bg-forest-50 text-xs font-bold tracking-wide text-forest-800">
          {step}
        </span>
        <span className={"rounded-full px-2.5 py-1 text-[10px] font-bold uppercase tracking-wider " + accent}>
          {title}
        </span>
      </div>
      <p className="mt-4 text-lg font-bold text-ink">{description}</p>
      <p className="mt-3 text-3xl font-extrabold tracking-tight text-ink">{value}</p>
      <p className="mt-1 min-h-8 text-xs leading-relaxed text-ink-muted">{detail}</p>
      <div className="mt-5 flex items-center justify-between border-t border-line pt-3 text-sm font-semibold text-forest-800">
        <span>{action}</span>
        <span aria-hidden="true" className="transition-transform group-hover:translate-x-1">→</span>
      </div>
    </article>
  </Link>
);

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

/**
 * Robin workflow dashboard.
 *
 * Counts are drawn from authenticated APIs. No stage is reported as complete
 * merely because a later-stage record exists: "Prepared" uses the current
 * READY_TO_APPLY application state, "Apply" uses the review queue's pending
 * count, and "Track" is the count of application records (not claimed
 * submissions). The Jobs API is cursor-paginated, so a next cursor is shown
 * with a plus sign rather than claiming the loaded page is the total catalogue.
 */
export const DashboardPage: React.FC = () => {
  const [health, setHealth] = useState<{ status: string } | null>(null);
  const [jobs, setJobs] = useState<Job[] | null>(null);
  const [jobsHasMore, setJobsHasMore] = useState(false);
  const [jobsError, setJobsError] = useState<string | null>(null);
  const [applications, setApplications] = useState<ApplicationSummary[] | null>(null);
  const [applicationsError, setApplicationsError] = useState<string | null>(null);
  const [reviewQueue, setReviewQueue] = useState<ReviewQueueSummary | null>(null);
  const [reviewQueueError, setReviewQueueError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<{ status: string }>('/system/health')
      .then(setHealth)
      .catch(() => setHealth({ status: 'DOWN' }));

    apiFetch<{ items: Job[]; next_cursor?: string }>('/jobs?limit=100')
      .then((result) => {
        setJobs(result.items || []);
        setJobsHasMore(Boolean(result.next_cursor));
      })
      .catch((error: unknown) => {
        setJobs(null);
        setJobsError(errorMessage(error, 'Could not load the job catalogue.'));
      });

    apiFetch<{ items: ApplicationSummary[] }>('/applications')
      .then((result) => setApplications(result.items || []))
      .catch((error: unknown) => {
        setApplications(null);
        setApplicationsError(errorMessage(error, 'Could not load application records.'));
      });

    apiFetch<ReviewQueueSummary>('/review-queue?includePaused=true')
      .then(setReviewQueue)
      .catch((error: unknown) => {
        setReviewQueue(null);
        setReviewQueueError(errorMessage(error, 'Could not load the review queue.'));
      });
  }, []);

  const preparedCount = applications
    ? applications.filter((application) => application.status === 'READY_TO_APPLY').length
    : 0;
  const pendingReviewCount = reviewQueue
    ? reviewQueue.pendingCount ?? (reviewQueue.items || []).filter((item) => item.decision === 'NEEDS_REVIEW').length
    : 0;
  const jobCount = jobs === null
    ? (jobsError ? 'Unavailable' : '…')
    : String(jobs.length) + (jobsHasMore ? '+' : '');
  const prepCount = applications === null
    ? (applicationsError ? 'Unavailable' : '…')
    : String(preparedCount);
  const reviewCount = reviewQueue === null
    ? (reviewQueueError ? 'Unavailable' : '…')
    : String(pendingReviewCount);
  const trackingCount = applications === null
    ? (applicationsError ? 'Unavailable' : '…')
    : String(applications.length);
  const backendUp = health?.status === 'UP';

  return (
    <PageShell>
      <div className="space-y-7">
        <PageHeader
          eyebrow="Your job search"
          title="Robin workflow"
          subtitle="One connected workspace to find relevant jobs, prepare applications, make decisions and track progress."
          actions={
            <span
              className={"inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-semibold " + (
                backendUp
                  ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
                  : 'border-amber-300 bg-amber-50 text-amber-800'
              )}
            >
              <span aria-hidden="true" className={"h-2 w-2 rounded-full " + (backendUp ? 'bg-emerald-500' : 'bg-amber-500')} />
              Backend {health?.status || 'checking…'}
            </span>
          }
        />

        <section aria-labelledby="workflow-stages-title" className="space-y-3">
          <div>
            <h2 id="workflow-stages-title" className="text-base font-bold text-ink">Four stages. One workflow.</h2>
            <p className="mt-1 text-sm text-ink-muted">
              The figures below come from Robin's APIs. Unavailable data is shown as unavailable, not as zero.
            </p>
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <WorkflowStageCard
              step="01"
              title="FIND"
              description="Discover relevant jobs"
              value={jobCount}
              detail={jobsError || (jobs === null ? 'Loading the job catalogue…' : 'Recent indexed jobs' + (jobsHasMore ? '; more results are available' : ''))}
              href="/jobs"
              action="Browse jobs"
              accent="bg-emerald-50 text-emerald-800"
            />
            <WorkflowStageCard
              step="02"
              title="PREP"
              description="Prepare your application"
              value={prepCount}
              detail={applicationsError || 'Application records currently marked READY_TO_APPLY'}
              href="/jobs"
              action="Open job preparation"
              accent="bg-sky-50 text-sky-800"
            />
            <WorkflowStageCard
              step="03"
              title="APPLY"
              description="Review before applying"
              value={reviewCount}
              detail={reviewQueueError || 'Pending human-review items; approval does not submit an application'}
              href="/review-queue"
              action="Open review queue"
              accent="bg-amber-50 text-amber-900"
            />
            <WorkflowStageCard
              step="04"
              title="TRACK"
              description="Track application progress"
              value={trackingCount}
              detail={applicationsError || 'Application records available to your account; not a submitted-application count'}
              href="/applications"
              action="Open tracker"
              accent="bg-violet-50 text-violet-800"
            />
          </div>
        </section>

        {jobsError && (
          <Alert tone="error">
            The job catalogue could not be loaded: {jobsError} Open the Jobs Feed to retry.
          </Alert>
        )}
        {applicationsError && (
          <Alert tone="error">
            Application metrics are unavailable: {applicationsError}
          </Alert>
        )}
        {reviewQueueError && (
          <Alert tone="error">
            Review queue metrics are unavailable: {reviewQueueError}
          </Alert>
        )}

        <section className="rounded-xl border border-line bg-surface shadow-card">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-5 py-3.5">
            <div>
              <h2 className="text-sm font-semibold text-ink">Recently discovered jobs</h2>
              <p className="mt-0.5 text-xs text-ink-muted">
                Select a posting to inspect its description and your own match result.
              </p>
            </div>
            <SecondaryButton href="/jobs">Open Jobs Feed</SecondaryButton>
          </div>

          {jobs === null && !jobsError ? (
            <Loading>Loading recent jobs…</Loading>
          ) : jobsError ? (
            <div className="p-5">
              <EmptyState title="Job listings are unavailable" body="Robin could not retrieve the job catalogue. The dashboard has not interpreted this as an empty feed." actions={<SecondaryButton href="/jobs">Retry in Jobs Feed</SecondaryButton>} />
            </div>
          ) : jobs && jobs.length === 0 ? (
            <div className="p-5">
              <EmptyState title="No jobs in the current index" body="Open the Jobs Feed to search the existing catalogue or run discovery from an enabled Greenhouse or Ashby source." actions={<SecondaryButton href="/jobs">Open FIND</SecondaryButton>} />
            </div>
          ) : (
            <div className="divide-y divide-line">
              {(jobs || []).slice(0, 5).map((job) => (
                <div key={job.id} className="flex flex-col gap-2 px-5 py-4 transition-colors hover:bg-cream-50/70 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <Link to={'/jobs/' + job.id} className="truncate text-sm font-semibold text-ink hover:text-forest-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600">
                        {job.title}
                      </Link>
                      <WorkplacePill type={job.remote_type} />
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-ink-muted">
                      <span className="font-medium text-ink-soft">{job.company_name_raw || 'Unknown company'}</span>
                      <span aria-hidden="true">·</span>
                      <span>{job.location_raw || 'Location not listed'}</span>
                    </div>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    <JobStatusPill status={job.status} />
                    <Link to={'/jobs/' + job.id} className="rounded-lg border border-line bg-surface px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50">
                      Inspect
                    </Link>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

        <p className="text-xs leading-relaxed text-ink-faint">
          Safety note: an approval or prepared application is not proof of submission. Live ATS submission remains disabled until a separate safety-reviewed release.
        </p>
      </div>
    </PageShell>
  );
};
