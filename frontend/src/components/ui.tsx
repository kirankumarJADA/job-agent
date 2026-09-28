import React from 'react';
import { Link } from 'react-router-dom';

/**
 * The Robin design system.
 *
 * A small set of presentation-only primitives that give every page the same
 * visual language: warm off-white canvas, white cards with hairline warm
 * borders and soft shadows, deep forest green as the single primary accent,
 * and pill-shaped status badges. Palettes and shadows live in
 * tailwind.config.js; this file is where composition happens.
 *
 * Everything here is purely presentational — pages keep their own state,
 * data fetching and handlers and simply compose these pieces.
 */

/* ------------------------------------------------------------------ */
/* Layout                                                              */
/* ------------------------------------------------------------------ */

/** Standard content column for a page: max width, breathing room. */
export const PageShell: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="mx-auto w-full max-w-shell px-4 py-6 sm:px-6 lg:py-8">{children}</div>
);

interface PageHeaderProps {
  eyebrow?: string;
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  actions?: React.ReactNode;
}

/** Title block used at the top of every page, with optional right-side actions. */
export const PageHeader: React.FC<PageHeaderProps> = ({ eyebrow, title, subtitle, actions }) => (
  <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
    <div className="min-w-0">
      {eyebrow && (
        <p className="text-[11px] font-semibold uppercase tracking-[0.14em] text-forest-600">
          {eyebrow}
        </p>
      )}
      <h1 className="mt-0.5 text-2xl font-bold tracking-tight text-ink">{title}</h1>
      {subtitle && <p className="mt-1 max-w-2xl text-sm leading-relaxed text-ink-muted">{subtitle}</p>}
    </div>
    {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
  </div>
);

/* ------------------------------------------------------------------ */
/* Surfaces                                                            */
/* ------------------------------------------------------------------ */

const surfaceClass =
  'rounded-xl border border-line bg-surface shadow-card';

/** White card on the cream canvas. */
export const Card: React.FC<{ className?: string; children: React.ReactNode }> = ({
  className = '',
  children,
}) => <div className={`${surfaceClass} ${className}`}>{children}</div>;

/** Card with a standard section header: title + optional right-side hint/actions. */
export const SectionCard: React.FC<{
  title: React.ReactNode;
  hint?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
  bodyClassName?: string;
  children: React.ReactNode;
}> = ({ title, hint, actions, className = '', bodyClassName = '', children }) => (
  <section className={`${surfaceClass} ${className}`}>
    <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-5 py-3.5">
      <div className="flex min-w-0 flex-wrap items-baseline gap-x-2.5">
        <h2 className="text-sm font-semibold text-ink">{title}</h2>
        {hint && <span className="text-xs text-ink-faint">{hint}</span>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
    <div className={`px-5 py-4 ${bodyClassName}`}>{children}</div>
  </section>
);

/* ------------------------------------------------------------------ */
/* Buttons                                                             */
/* ------------------------------------------------------------------ */

const focusRing =
  'focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 focus-visible:ring-offset-2 focus-visible:ring-offset-cream-50';

export const PrimaryButton: React.FC<
  React.ButtonHTMLAttributes<HTMLButtonElement> & { href?: string }
> = ({ className = '', href, children, ...rest }) => {
  const classes = `inline-flex items-center justify-center gap-1.5 rounded-lg bg-forest-900 px-4 py-2 text-sm font-semibold text-cream-50 transition-colors hover:bg-forest-800 disabled:cursor-not-allowed disabled:opacity-50 ${focusRing} ${className}`;
  if (href) {
    return (
      <Link to={href} className={classes}>
        {children}
      </Link>
    );
  }
  return (
    <button className={classes} {...rest}>
      {children}
    </button>
  );
};

export const SecondaryButton: React.FC<
  React.ButtonHTMLAttributes<HTMLButtonElement> & { href?: string }
> = ({ className = '', href, children, ...rest }) => {
  const classes = `inline-flex items-center justify-center gap-1.5 rounded-lg border border-line bg-surface px-4 py-2 text-sm font-semibold text-ink-soft transition-colors hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900 disabled:cursor-not-allowed disabled:opacity-50 ${focusRing} ${className}`;
  if (href) {
    return (
      <Link to={href} className={classes}>
        {children}
      </Link>
    );
  }
  return (
    <button className={classes} {...rest}>
      {children}
    </button>
  );
};

/* ------------------------------------------------------------------ */
/* Pills and chips                                                     */
/* ------------------------------------------------------------------ */

export type PillTone = 'forest' | 'emerald' | 'amber' | 'red' | 'slate' | 'sky';

const PILL_TONES: Record<PillTone, string> = {
  forest: 'border-forest-200 bg-forest-50 text-forest-800',
  emerald: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  amber: 'border-amber-300 bg-amber-50 text-amber-800',
  red: 'border-red-200 bg-red-50 text-red-700',
  slate: 'border-line bg-surface-sunken text-ink-soft',
  sky: 'border-sky-200 bg-sky-50 text-sky-800',
};

/** Rounded pill badge for statuses, workplace types, recommendations. */
export const StatusPill: React.FC<{ tone?: PillTone; className?: string; children: React.ReactNode }> = ({
  tone = 'slate',
  className = '',
  children,
}) => (
  <span
    className={`inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2.5 py-0.5 text-[11px] font-semibold ${PILL_TONES[tone]} ${className}`}
  >
    {children}
  </span>
);

/** Clickable/toggleable pill used for filters and workplace-type checkboxes. */
export const FilterPill: React.FC<{
  selected: boolean;
  onClick: () => void;
  children: React.ReactNode;
}> = ({ selected, onClick, children }) => (
  <button
    type="button"
    aria-pressed={selected}
    onClick={onClick}
    className={`rounded-full border px-3 py-1 text-xs font-semibold transition-colors ${focusRing} ${
      selected
        ? 'border-forest-700 bg-forest-900 text-cream-50'
        : 'border-line bg-surface text-ink-soft hover:border-forest-300 hover:bg-forest-50'
    }`}
  >
    {children}
  </button>
);

/** Small non-interactive evidence/skill chip. */
export const Chip: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span className="inline-flex items-center whitespace-nowrap rounded-md border border-line bg-surface-sunken px-2 py-0.5 text-[11px] font-medium text-ink-soft">
    {children}
  </span>
);

/* ------------------------------------------------------------------ */
/* Metric cards                                                        */
/* ------------------------------------------------------------------ */

export const MetricCard: React.FC<{
  label: React.ReactNode;
  value: React.ReactNode;
  hint?: React.ReactNode;
  accent?: React.ReactNode;
}> = ({ label, value, hint, accent }) => (
  <div className={surfaceClass}>
    <div className="p-5">
      <div className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-faint">
        {label}
      </div>
      <div className="mt-2 flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="text-2xl font-bold tracking-tight text-ink">{value}</span>
        {accent && <span className="text-xs font-semibold text-forest-600">{accent}</span>}
      </div>
      {hint && <div className="mt-1 text-xs text-ink-muted">{hint}</div>}
    </div>
  </div>
);

/** Kept as an alias — earlier pages referred to StatCard. */
export const StatCard = MetricCard;

/* ------------------------------------------------------------------ */
/* States                                                              */
/* ------------------------------------------------------------------ */

export const EmptyState: React.FC<{
  title: React.ReactNode;
  body?: React.ReactNode;
  actions?: React.ReactNode;
}> = ({ title, body, actions }) => (
  <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-line bg-surface px-6 py-12 text-center">
    <div className="flex h-10 w-10 items-center justify-center rounded-full bg-forest-50 text-forest-700">
      <svg viewBox="0 0 20 20" fill="none" className="h-5 w-5" aria-hidden="true">
        <path
          d="M10 3.5a6.5 6.5 0 0 1 6.5 6.5c0 2.2-1.1 4.2-2.8 5.4l-.5 2.1H6.8l-.5-2.1A6.5 6.5 0 0 1 10 3.5Z"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinejoin="round"
        />
        <path d="M8 17.5h4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      </svg>
    </div>
    <p className="mt-3 text-sm font-semibold text-ink">{title}</p>
    {body && <p className="mt-1 max-w-md text-xs leading-relaxed text-ink-muted">{body}</p>}
    {actions && <div className="mt-4 flex flex-wrap items-center justify-center gap-2">{actions}</div>}
  </div>
);

export const Loading: React.FC<{ children?: React.ReactNode }> = ({ children }) => (
  <div role="status" className="flex flex-col items-center justify-center px-6 py-16 text-center">
    <span
      aria-hidden="true"
      className="h-6 w-6 animate-spin rounded-full border-2 border-forest-200 border-t-forest-700"
    />
    <span className="mt-3 text-sm text-ink-muted">{children ?? 'Loading…'}</span>
  </div>
);

export const Alert: React.FC<{ tone?: 'success' | 'error' | 'info'; children: React.ReactNode }> = ({
  tone = 'info',
  children,
}) => {
  const tones = {
    success: 'border-emerald-200 bg-emerald-50 text-emerald-900',
    error: 'border-red-200 bg-red-50 text-red-800',
    info: 'border-forest-200 bg-forest-50 text-forest-900',
  } as const;
  return (
    <div role={tone === 'error' ? 'alert' : 'status'} className={`rounded-lg border px-4 py-3 text-sm leading-relaxed ${tones[tone]}`}>
      {children}
    </div>
  );
};

/* ------------------------------------------------------------------ */
/* Tables                                                              */
/* ------------------------------------------------------------------ */

export const DataTable: React.FC<{
  columns: React.ReactNode[];
  rows: React.ReactNode[][];
  emptyMessage?: string;
}> = ({ columns, rows, emptyMessage = 'Nothing here yet.' }) => (
  <div className="overflow-x-auto">
    <table className="w-full min-w-[640px] text-left text-sm">
      <thead>
        <tr className="border-b border-line">
          {columns.map((col, i) => (
            <th
              key={i}
              scope="col"
              className="whitespace-nowrap px-3 py-2.5 text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint first:pl-0 last:pr-0"
            >
              {col}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-line">
        {rows.length === 0 ? (
          <tr>
            <td colSpan={columns.length} className="px-3 py-8 text-center text-sm text-ink-muted">
              {emptyMessage}
            </td>
          </tr>
        ) : (
          rows.map((cells, i) => (
            <tr key={i} className="transition-colors hover:bg-cream-50/60">
              {cells.map((cell, j) => (
                <td key={j} className="px-3 py-3 align-middle text-ink-soft first:pl-0 last:pr-0">
                  {cell}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  </div>
);

/* ------------------------------------------------------------------ */
/* Form controls                                                       */
/* ------------------------------------------------------------------ */

export const TextInput: React.FC<React.InputHTMLAttributes<HTMLInputElement>> = ({
  className = '',
  ...rest
}) => (
  <input
    className={`w-full rounded-lg border border-line bg-surface px-3.5 py-2 text-sm text-ink placeholder-ink-faint transition-colors focus:border-forest-600 focus:outline-none focus:ring-2 focus:ring-forest-600/20 disabled:cursor-not-allowed disabled:opacity-60 ${className}`}
    {...rest}
  />
);

export const Select: React.FC<React.SelectHTMLAttributes<HTMLSelectElement>> = ({
  className = '',
  children,
  ...rest
}) => (
  <select
    className={`w-full rounded-lg border border-line bg-surface px-3 py-2 text-sm text-ink transition-colors focus:border-forest-600 focus:outline-none focus:ring-2 focus:ring-forest-600/20 ${className}`}
    {...rest}
  >
    {children}
  </select>
);

export const Textarea: React.FC<React.TextareaHTMLAttributes<HTMLTextAreaElement>> = ({
  className = '',
  ...rest
}) => (
  <textarea
    className={`w-full rounded-lg border border-line bg-surface px-3.5 py-2 text-sm text-ink placeholder-ink-faint transition-colors focus:border-forest-600 focus:outline-none focus:ring-2 focus:ring-forest-600/20 ${className}`}
    {...rest}
  />
);

export const Label: React.FC<{ htmlFor?: string; children: React.ReactNode }> = ({
  htmlFor,
  children,
}) => (
  <label htmlFor={htmlFor} className="mb-1 block text-xs font-semibold text-ink-soft">
    {children}
  </label>
);

/* ------------------------------------------------------------------ */
/* Domain pieces                                                       */
/* ------------------------------------------------------------------ */

const STATUS_TONES: Record<string, PillTone> = {
  DISCOVERED: 'sky',
  ANALYSED: 'sky',
  SCORED: 'forest',
  DECIDED: 'forest',
  FILTERED_OUT: 'slate',
  ARCHIVED: 'slate',
  PIPELINE_ERROR: 'red',
  COMPLETED: 'emerald',
  RUNNING: 'amber',
  FAILED: 'red',
  CANCELLED: 'slate',
  APPLY: 'emerald',
  REVIEW: 'amber',
  SKIP: 'slate',
};

/** Pill for a Robin pipeline/app/recommendation status, tone mapped centrally. */
export const JobStatusPill: React.FC<{ status: string }> = ({ status }) => (
  <StatusPill tone={STATUS_TONES[status] ?? 'slate'}>{status}</StatusPill>
);

export type WorkplaceType = NonNullable<
  import('../types').Job['remote_type']
> | 'UNKNOWN' | string;

const WORKPLACE_TONES: Record<string, PillTone> = {
  REMOTE: 'emerald',
  HYBRID: 'forest',
  ONSITE: 'slate',
  UNKNOWN: 'slate',
};

export const WorkplacePill: React.FC<{ type?: string }> = ({ type }) =>
  type ? <StatusPill tone={WORKPLACE_TONES[type] ?? 'slate'}>{type}</StatusPill> : null;

/** Salary line, e.g. "£50,000 – £65,000"; falls back to a generic label. */
export const SalaryText: React.FC<{ min?: number; max?: number; period?: string }> = ({
  min,
  max,
  period,
}) => {
  if (!min) return <span className="font-medium text-ink-soft">Competitive</span>;
  const suffix = period ? ` ${period}` : ' / yr';
  return (
    <span className="font-semibold text-forest-700">
      {min.toLocaleString()} – {max ? `£${max.toLocaleString()}` : '£—'}
      {suffix}
    </span>
  );
};

/** Compact, scannable job card used in the feed and dashboards. */
export const JobCard: React.FC<{
  job: import('../types').Job;
  footer?: React.ReactNode;
  actions?: React.ReactNode;
}> = ({ job, footer, actions }) => {
  // Some imported postings have no usable timestamps; never render "Invalid Date".
  const seen = toShortDate(job.first_seen_at);
  const metaSegments = [
    job.location_raw || 'United Kingdom',
    minSalary(job.salary_min, job.salary_max),
    seen ? `Seen ${seen}` : null,
  ].filter((segment): segment is string => segment !== null);

  return (
    <article className={`${surfaceClass} transition-shadow hover:shadow-raise`}>
      <div className="p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="text-base font-semibold leading-snug text-ink">
              <Link
                to={`/jobs/${job.id}`}
                className="rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 hover:text-forest-700"
              >
                {job.title}
              </Link>
            </h3>
            <p className="mt-0.5 truncate text-sm text-ink-muted">
              {job.company_name_raw || 'Company not stated'}
            </p>
          </div>
          <div className="flex shrink-0 flex-wrap items-center justify-end gap-1.5">
            <WorkplacePill type={job.remote_type} />
            <JobStatusPill status={job.status} />
          </div>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-muted">
          <span className="inline-flex items-center gap-1">
            <svg viewBox="0 0 20 20" fill="none" className="h-3.5 w-3.5 shrink-0" aria-hidden="true">
              <path
                d="M10 18s6-5.1 6-9.6A6 6 0 0 0 4 8.4C4 12.9 10 18 10 18Z"
                stroke="currentColor"
                strokeWidth="1.4"
              />
              <circle cx="10" cy="8.4" r="2.1" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            {metaSegments[0] ?? 'United Kingdom'}
          </span>
          {metaSegments.slice(1).map((segment, i) => (
            <span key={i} className="inline-flex items-center gap-2">
              <span aria-hidden="true" className="text-ink-faint">·</span>
              <span className={segment.startsWith('£') ? 'font-medium' : ''}>{segment}</span>
            </span>
          ))}
        </div>

        {job.skills_extracted && job.skills_extracted.length > 0 && (
          <div className="mt-3 flex flex-wrap gap-1.5">
            {job.skills_extracted.slice(0, 6).map((skill, i) => (
              <Chip key={`${skill}-${i}`}>{skill}</Chip>
            ))}
            {job.skills_extracted.length > 6 && <Chip>+{job.skills_extracted.length - 6}</Chip>}
          </div>
        )}
      </div>

      {(footer || actions) && (
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line bg-cream-50/60 px-5 py-3">
          <div className="min-w-0">{footer}</div>
          <div className="flex shrink-0 items-center gap-2">{actions}</div>
        </div>
      )}
    </article>
  );
};

/** Locale short date, or null when the input is absent/unparseable. */
function toShortDate(value?: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString();
}

function minSalary(min?: number, max?: number): string {
  if (!min) return 'Competitive';
  return max ? `£${min.toLocaleString()} – £${max.toLocaleString()}` : `£${min.toLocaleString()}+`;
}

/* ------------------------------------------------------------------ */
/* Document preview                                                    */
/* ------------------------------------------------------------------ */

/**
 * Readable preview for generated documents (tailored CV markdown, cover
 * letters, drafted answers). Wide by design — the old narrow dark scroll box
 * hid the document; this shows it at full column width with generous height.
 */
export const DocumentPreview: React.FC<{ children: string; className?: string }> = ({
  children,
  className = '',
}) => (
  <div
    className={`overflow-y-auto rounded-lg border border-line bg-surface p-4 text-[13px] leading-relaxed text-ink-soft sm:p-5 ${className}`}
  >
    {children}
  </div>
);

/** Monospaced trace/JSON output block, light themed. */
export const TraceBlock: React.FC<{ children: string }> = ({ children }) => (
  <pre className="overflow-x-auto rounded-lg border border-line bg-surface-sunken p-4 font-mono text-xs leading-relaxed text-forest-800">
    {children}
  </pre>
);
