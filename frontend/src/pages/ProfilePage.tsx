import React, { useEffect, useMemo, useState } from 'react';
import { apiFetch } from '../api/client';
import { Profile } from '../types';
import {
  Alert,
  EmptyState,
  Loading,
  PageHeader,
  PageShell,
  PrimaryButton,
  SecondaryButton,
  SectionCard,
  StatusPill,
  TextInput,
  Textarea,
} from '../components/ui';
import { ResumeImportWizard } from '../components/profile/ResumeImportWizard';

/**
 * Master Profile / Master CV.
 *
 * Presentation-only redesign: every field, endpoint call and handler is
 * unchanged (save profile, add/update/delete evidence, complete setup, purge
 * with password confirmation). The evidence sections now render as clean white
 * cards with readable inputs instead of dark console-style grids.
 */
export const ProfilePage: React.FC = () => {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [evidenceCount, setEvidenceCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [newEducation, setNewEducation] = useState({ institution: '', qualification: '', field: '', startYear: '', endYear: '', grade: '' });
  const [newExperience, setNewExperience] = useState({ company: '', title: '', startMonth: '', endMonth: '', location: '', bullets: '' });
  const [newProject, setNewProject] = useState({ name: '', summary: '', url: '' });
  const [newSkill, setNewSkill] = useState({ name: '', category: '', mastery: '3', years: '' });
  const [newCertification, setNewCertification] = useState({ name: '', issuer: '', issuedOn: '', credentialId: '' });
  const [showPurge, setShowPurge] = useState(false);
  const [purgePassword, setPurgePassword] = useState('');
  const [showResumeImport, setShowResumeImport] = useState(false);

  const completenessScore = useMemo(() => {
    if (!profile) return 0;
    let score = 0;
    if (profile.headline) score += 10;
    if (profile.location) score += 10;
    if (profile.phone) score += 10;
    if (profile.professional_summary) score += 15;
    if (profile.experiences && profile.experiences.length > 0) score += 20;
    if (profile.education && profile.education.length > 0) score += 15;
    if (profile.skills && profile.skills.length > 0) score += 10;
    if (profile.projects && profile.projects.length > 0) score += 10;
    return Math.min(100, score);
  }, [profile]);

  const fetchProfile = async () => {
    setLoading(true); setError(null);
    try {
      const response = await apiFetch<any>('/profile');
      // GET /profile returns the profile record NESTED under `profile`, with
      // the evidence arrays (skills, experiences, education, projects,
      // certifications) as TOP-LEVEL siblings. Merging both is required:
      // normalizing `response.profile` alone always rendered empty sections,
      // which looked like added records were never persisted.
      const raw = { ...response, ...(response.profile ?? {}) };
      setProfile(normalizeProfile(raw));
      setEvidenceCount((response.evidence ?? []).length);
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to load Master Profile'); }
    finally { setLoading(false); }
  };
  useEffect(() => { fetchProfile(); }, []);

  const saveProfile = async (event: React.FormEvent) => {
    event.preventDefault(); if (!profile) return;
    try {
      await apiFetch<Profile>('/profile', { method: 'PUT', body: JSON.stringify({
        headline: profile.headline, phone: profile.phone, location: profile.location,
        professionalSummary: profile.professional_summary, links: profile.links ?? {},
        workEligibility: profile.work_eligibility, careerGoals: profile.career_goals,
      }) });
      // Re-fetch instead of normalizing the PUT response: it returns only the
      // flat profile record, and normalizing it here would blank out the
      // rendered evidence sections (same shape mismatch as fetchProfile).
      await fetchProfile();
      setMessage('Master Profile saved. Future jobs will reuse this verified evidence.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Save failed'); }
  };

  const add = async (path: string, body: unknown, reset: () => void) => {
    try { await apiFetch(path, { method: 'POST', body: JSON.stringify(body) }); reset(); await fetchProfile(); setMessage('Master evidence saved.'); }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not save evidence'); }
  };
  const update = async (path: string, id: string, body: unknown, reset: () => void) => {
    try { await apiFetch(`${path}/${id}`, { method: 'PUT', body: JSON.stringify(body) }); reset(); await fetchProfile(); setMessage('Master evidence updated. Historical tailored CVs were not changed.'); }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not update evidence'); }
  };
  const remove = async (path: string, id: string) => {
    try { await apiFetch(`${path}/${id}`, { method: 'DELETE' }); await fetchProfile(); setMessage('Master evidence removed. Historical tailored CVs were not changed.'); }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not remove evidence'); }
  };
  const completeSetup = async () => {
    try { await apiFetch('/profile/complete', { method: 'POST', body: '{}' }); await fetchProfile(); setMessage('Master Profile setup is complete.'); }
    catch (e) { setError(e instanceof Error ? e.message : 'Complete the required evidence first'); }
  };
  const purge = async (event: React.FormEvent) => {
    event.preventDefault();
    try { await apiFetch('/auth/purge-my-data', { method: 'POST', body: JSON.stringify({ confirmationPassword: purgePassword }) }); setShowPurge(false); setPurgePassword(''); await fetchProfile(); }
    catch (e) { setError(e instanceof Error ? e.message : 'Purge failed'); }
  };

  if (loading) {
    return <PageShell><Loading>Loading Master Profile…</Loading></PageShell>;
  }
  if (!profile) {
    return (
      <PageShell>
        <div className="max-w-3xl">
          <EmptyState
            title="No Master Profile yet"
            body="Ask the account owner to initialize setup. The Master Profile is the single source of verified career evidence."
          />
        </div>
      </PageShell>
    );
  }

  const p = profile;
  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Career evidence"
          title="Master Profile"
          subtitle="Enter career evidence once. Tailored CVs are immutable job-specific snapshots built from this record."
          actions={
            <>
              <StatusPill tone={p.setup_status === 'READY' ? 'emerald' : 'amber'}>
                {p.setup_status === 'READY' ? 'Setup complete' : 'Setup incomplete'}
              </StatusPill>
              <span className="text-xs text-ink-muted">revision {p.master_revision}</span>
              <SecondaryButton type="button" onClick={() => setShowResumeImport(true)}>
                Import from Resume
              </SecondaryButton>
              <button
                type="button"
                onClick={() => setShowPurge(true)}
                className="rounded-lg border border-red-200 px-3 py-2 text-xs font-semibold text-red-700 transition-colors hover:bg-red-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-600"
              >
                Purge data
              </button>
            </>
          }
        />

        {evidenceCount > 0 && (
          <Alert tone="success">{evidenceCount} provenance-linked verified evidence claims on record.</Alert>
        )}
        <div className="flex items-center justify-between rounded-lg border border-line bg-cream-50/70 p-4" aria-label="Profile completeness">
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold uppercase tracking-wider text-ink-muted">Profile Truth & Completeness</span>
              <span className="rounded bg-forest-100 px-1.5 py-0.5 text-xs font-bold text-forest-800">{completenessScore}%</span>
            </div>
            <p className="text-xs text-ink-muted">
              Robin guarantees 100% factual grounding against these {evidenceCount} verified evidence items. No hallucinated skills or dates will ever be generated.
            </p>
          </div>
          <div className="w-32 h-2.5 rounded-full bg-slate-200 overflow-hidden shrink-0 ml-4">
            <div className="h-full bg-forest-600 transition-all duration-300" style={{ width: `${completenessScore}%` }} />
          </div>
        </div>
        {message && <Alert tone="info">{message}</Alert>}
        {error && <Alert tone="error">{error}</Alert>}

        <SectionCard title="Canonical identity & summary" bodyClassName="space-y-4">
          <form onSubmit={saveProfile} className="space-y-4">
            <div className="grid gap-4 md:grid-cols-3">
              <FieldInput label="Headline" value={p.headline ?? ''} onChange={(v) => setProfile({ ...p, headline: v })} />
              <FieldInput label="Location" value={p.location ?? ''} onChange={(v) => setProfile({ ...p, location: v })} />
              <FieldInput label="Phone" value={p.phone ?? ''} onChange={(v) => setProfile({ ...p, phone: v })} />
            </div>
            <div>
              <label htmlFor="professional-summary" className="mb-1 block text-xs font-semibold text-ink-soft">
                Professional summary
              </label>
              <Textarea
                id="professional-summary"
                value={p.professional_summary ?? ''}
                onChange={(e) => setProfile({ ...p, professional_summary: e.target.value })}
                rows={3}
              />
            </div>
            <PrimaryButton type="submit">Save Master Profile</PrimaryButton>
          </form>
        </SectionCard>

        <EvidenceSection title="Education / MSc and degrees" items={p.education} fields={['institution', 'qualification', 'field', 'startYear', 'endYear', 'grade']} values={newEducation} setValues={setNewEducation} onAdd={() => add('/profile/education', { ...newEducation, startYear: newEducation.startYear ? Number(newEducation.startYear) : null, endYear: newEducation.endYear ? Number(newEducation.endYear) : null }, () => setNewEducation({ institution: '', qualification: '', field: '', startYear: '', endYear: '', grade: '' }))} onUpdate={(id, values, reset) => update('/profile/education', id, { ...values, startYear: values.startYear ? Number(values.startYear) : null, endYear: values.endYear ? Number(values.endYear) : null }, reset)} onDelete={(id) => remove('/profile/education', id)} />
        <EvidenceSection
          title="Work experience"
          items={p.experiences}
          fields={['company', 'title', 'startMonth', 'endMonth', 'location', 'bullets']}
          values={newExperience}
          setValues={setNewExperience}
          onAdd={() => {
            const start = parseDateInput(newExperience.startMonth);
            const end = parseDateInput(newExperience.endMonth);
            add(
              '/profile/experiences',
              {
                ...newExperience,
                startMonth: start.date,
                endMonth: end.date,
                startDatePrecision: start.precision,
                endDatePrecision: end.precision,
                bullets: newExperience.bullets ? newExperience.bullets.split('\n').map((text) => ({ text })) : [],
              },
              () => setNewExperience({ company: '', title: '', startMonth: '', endMonth: '', location: '', bullets: '' })
            );
          }}
          onUpdate={(id, values, reset) => {
            const start = parseDateInput(values.startMonth, (values as any).startDatePrecision);
            const end = parseDateInput(values.endMonth, (values as any).endDatePrecision);
            update(
              '/profile/experiences',
              id,
              {
                ...values,
                startMonth: start.date,
                endMonth: end.date,
                startDatePrecision: start.precision,
                endDatePrecision: end.precision,
                bullets: values.bullets ? values.bullets.split(/\\n|\n/).map((text) => ({ text })) : [],
              },
              reset
            );
          }}
          onDelete={(id) => remove('/profile/experiences', id)}
        />
        <EvidenceSection title="Projects" items={p.projects} fields={['name', 'summary', 'url']} values={newProject} setValues={setNewProject} onAdd={() => add('/profile/projects', { ...newProject, bullets: [] }, () => setNewProject({ name: '', summary: '', url: '' }))} onUpdate={(id, values, reset) => update('/profile/projects', id, { ...values, bullets: [] }, reset)} onDelete={(id) => remove('/profile/projects', id)} />
        <EvidenceSection title="Verified skills" items={p.skills} fields={['name', 'category', 'mastery', 'years']} values={newSkill} setValues={setNewSkill} onAdd={() => add('/profile/skills', { ...newSkill, mastery: Number(newSkill.mastery), years: newSkill.years ? Number(newSkill.years) : null }, () => setNewSkill({ name: '', category: '', mastery: '3', years: '' }))} onUpdate={(id, values, reset) => update('/profile/skills', id, { ...values, mastery: Number(values.mastery), years: values.years ? Number(values.years) : null }, reset)} onDelete={(id) => remove('/profile/skills', id)} />
        <EvidenceSection
          title="Certifications"
          items={p.certifications}
          fields={['name', 'issuer', 'issuedOn', 'credentialId']}
          values={newCertification}
          setValues={setNewCertification}
          onAdd={() => {
            const issued = parseDateInput(newCertification.issuedOn);
            add(
              '/profile/certifications',
              {
                ...newCertification,
                issuedOn: issued.date,
                issuedOnPrecision: issued.precision,
              },
              () => setNewCertification({ name: '', issuer: '', issuedOn: '', credentialId: '' })
            );
          }}
          onUpdate={(id, values, reset) => {
            const issued = parseDateInput(values.issuedOn, (values as any).issuedOnPrecision);
            update(
              '/profile/certifications',
              id,
              {
                ...values,
                issuedOn: issued.date,
                issuedOnPrecision: issued.precision,
              },
              reset
            );
          }}
          onDelete={(id) => remove('/profile/certifications', id)}
        />

        <div className="flex justify-end">
          <button
            type="button"
            onClick={completeSetup}
            className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-forest-700 px-5 py-2.5 text-sm font-semibold text-cream-50 transition-colors hover:bg-forest-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 focus-visible:ring-offset-2"
          >
            Mark Master Profile complete
          </button>
        </div>

        {showResumeImport && (
          <ResumeImportWizard
            onClose={() => setShowResumeImport(false)}
            onImported={() => { fetchProfile(); setMessage('Resume import applied to your Master Profile.'); }}
          />
        )}

        {showPurge && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/60 p-4" role="dialog" aria-modal="true" aria-labelledby="purge-title">
            <form onSubmit={purge} className="w-full max-w-md space-y-4 rounded-xl border border-line bg-surface p-6 shadow-pop">
              <div>
                <h2 id="purge-title" className="text-lg font-bold text-red-700">Purge career data</h2>
                <p className="mt-1 text-xs leading-relaxed text-ink-muted">
                  This removes the editable Master Profile. Historical tailored CV snapshots remain immutable.
                </p>
              </div>
              <div>
                <label htmlFor="purge-password" className="mb-1 block text-xs font-semibold text-ink-soft">
                  Account password
                </label>
                <input
                  id="purge-password"
                  type="password"
                  required
                  value={purgePassword}
                  onChange={(e) => setPurgePassword(e.target.value)}
                  className="w-full rounded-lg border border-line bg-surface px-3.5 py-2 text-sm text-ink focus:border-forest-600 focus:outline-none focus:ring-2 focus:ring-forest-600/20"
                />
              </div>
              <div className="flex justify-end gap-2">
                <SecondaryButton type="button" onClick={() => setShowPurge(false)}>Cancel</SecondaryButton>
                <button
                  type="submit"
                  className="inline-flex items-center justify-center rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-600 focus-visible:ring-offset-2"
                >
                  Confirm purge
                </button>
              </div>
            </form>
          </div>
        )}
      </div>
    </PageShell>
  );
};

const FieldInput: React.FC<{ label: string; value: string; onChange: (v: string) => void }> = ({
  label,
  value,
  onChange,
}) => {
  const id = `profile-${label.toLowerCase().replace(/\s+/g, '-')}`;
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-xs font-semibold text-ink-soft">{label}</label>
      <TextInput id={id} value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  );
};

function normalizeProfile(raw: any): Profile {
  return {
    ...raw,
    professional_summary: raw.professional_summary ?? raw.professionalSummary ?? '',
    master_revision: raw.master_revision ?? raw.masterRevision ?? 1,
    setup_status: raw.setup_status ?? raw.setupStatus ?? 'INCOMPLETE',
    work_eligibility: raw.work_eligibility ?? raw.workEligibility ?? {},
    career_goals: raw.career_goals ?? raw.careerGoals ?? {},
    experiences: raw.experiences ?? [],
    education: raw.education ?? [],
    projects: raw.projects ?? [],
    skills: raw.skills ?? [],
    certifications: raw.certifications ?? [],
  } as Profile;
}

export function formatDateForInput(
  dateStr?: string | null,
  precision?: string | null
): string {
  if (!dateStr) return '';
  const clean = String(dateStr).trim();
  if (!clean) return '';
  if (precision === 'YEAR') {
    const match = clean.match(/^(\d{4})/);
    return match ? match[1] : clean;
  }
  if (precision === 'MONTH') {
    const match = clean.match(/^(\d{4}-\d{2})/);
    return match ? match[1] : clean;
  }
  const match = clean.match(/^(\d{4}-\d{2})-\d{2}$/);
  if (match) {
    return match[1];
  }
  return clean;
}

export function parseDateInput(
  raw?: string | null,
  existingPrecision?: string | null
): { date: string | null; precision: 'YEAR' | 'MONTH' | null } {
  if (!raw || !raw.trim()) {
    return { date: null, precision: null };
  }
  const trimmed = raw.trim();
  if (/^\d{4}$/.test(trimmed)) {
    return { date: `${trimmed}-01-01`, precision: 'YEAR' };
  }
  if (/^\d{4}-\d{2}$/.test(trimmed)) {
    return { date: `${trimmed}-01`, precision: 'MONTH' };
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(trimmed)) {
    if (existingPrecision === 'YEAR' && trimmed.endsWith('-01-01')) {
      return { date: trimmed, precision: 'YEAR' };
    }
    return { date: trimmed, precision: (existingPrecision as 'YEAR' | 'MONTH') || 'MONTH' };
  }
  return { date: trimmed, precision: (existingPrecision as 'YEAR' | 'MONTH') || 'MONTH' };
}

function EvidenceSection({ title, items, fields, values, setValues, onAdd, onUpdate, onDelete }: { title: string; items: any[]; fields: string[]; values: Record<string, string>; setValues: (v: any) => void; onAdd: () => void; onUpdate?: (id: string, values: Record<string, string>, reset: () => void) => void; onDelete?: (id: string) => void }) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const valueFor = (item: any, field: string) => {
    const snake = field.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
    if (field === 'bullets') return (item.bullets ?? []).map((bullet: any) => bullet.text ?? '').join('\\n');
    const rawVal = item[field] ?? item[snake];
    if (field === 'startMonth') {
      const precision = item.startDatePrecision ?? item.start_date_precision;
      return formatDateForInput(rawVal, precision);
    }
    if (field === 'endMonth') {
      const precision = item.endDatePrecision ?? item.end_date_precision;
      return formatDateForInput(rawVal, precision);
    }
    if (field === 'issuedOn') {
      const precision = item.issuedOnPrecision ?? item.issued_on_precision;
      return formatDateForInput(rawVal, precision);
    }
    return String(rawVal ?? '');
  };

  const handleStartEdit = (item: any) => {
    setEditingId(item.id);
    const initial: Record<string, any> = Object.fromEntries(
      fields.map((field) => [field, valueFor(item, field)])
    );
    if (item.startDatePrecision || item.start_date_precision) {
      initial.startDatePrecision = item.startDatePrecision ?? item.start_date_precision;
    }
    if (item.endDatePrecision || item.end_date_precision) {
      initial.endDatePrecision = item.endDatePrecision ?? item.end_date_precision;
    }
    if (item.issuedOnPrecision || item.issued_on_precision) {
      initial.issuedOnPrecision = item.issuedOnPrecision ?? item.issued_on_precision;
    }
    setValues(initial);
  };

  const reset = () => { setEditingId(null); };

  const renderDateBadge = (item: any) => {
    const startRaw = item.startMonth ?? item.start_month;
    const endRaw = item.endMonth ?? item.end_month;
    const startPrec = item.startDatePrecision ?? item.start_date_precision;
    const endPrec = item.endDatePrecision ?? item.end_date_precision;
    if (startRaw) {
      const formattedStart = formatDateForInput(startRaw, startPrec);
      const formattedEnd = endRaw ? formatDateForInput(endRaw, endPrec) : 'Present';
      return `${formattedStart} – ${formattedEnd}`;
    }
    const issuedRaw = item.issuedOn ?? item.issued_on;
    const issuedPrec = item.issuedOnPrecision ?? item.issued_on_precision;
    if (issuedRaw) {
      return `Issued ${formatDateForInput(issuedRaw, issuedPrec)}`;
    }
    const startYear = item.startYear ?? item.start_year;
    const endYear = item.endYear ?? item.end_year;
    if (startYear || endYear) {
      return `${startYear ?? ''} – ${endYear ?? 'Present'}`;
    }
    return null;
  };

  return (
    <SectionCard
      title={title}
      hint={`${items?.length ?? 0} reusable evidence records`}
      bodyClassName="space-y-4"
    >
      <div className="grid gap-2 md:grid-cols-3 lg:grid-cols-4">
        {fields.map((field) => (
          <TextInput
            key={field}
            placeholder={field}
            aria-label={`${title} — new record ${field}`}
            value={values[field] ?? ''}
            onChange={(e) => setValues({ ...values, [field]: e.target.value })}
          />
        ))}
        {editingId && onUpdate ? (
          <SecondaryButton type="button" onClick={() => onUpdate(editingId, values, reset)}>Save edit</SecondaryButton>
        ) : (
          <SecondaryButton type="button" onClick={onAdd}>Add verified record</SecondaryButton>
        )}
        {editingId && <SecondaryButton type="button" onClick={reset}>Cancel</SecondaryButton>}
      </div>

      {(items ?? []).length === 0 ? (
        <p className="rounded-lg border border-dashed border-line bg-cream-50 px-4 py-3 text-xs text-ink-muted">
          No records yet — add the first verified entry above.
        </p>
      ) : (
        <div className="grid gap-2 md:grid-cols-2">
          {(items ?? []).map((item: any) => {
            const dateBadge = renderDateBadge(item);
            const mainTitle = item.title && item.company
              ? `${item.title} — ${item.company}`
              : item.name ?? item.title ?? item.qualification ?? item.company;
            const subtitle = item.title && item.company
              ? (item.location ?? '')
              : item.summary ?? item.institution ?? item.issuer ?? item.category ?? '';
            return (
              <div key={item.id} className="rounded-lg border border-line bg-cream-50/70 p-3.5 text-xs">
                <div className="font-semibold text-ink">{mainTitle}</div>
                {subtitle && <div className="mt-0.5 text-ink-muted">{subtitle}</div>}
                {dateBadge && <div className="mt-0.5 font-medium text-ink-muted">{dateBadge}</div>}
                <div className="mt-2 flex items-center justify-between">
                  <StatusPill tone="emerald">User-verified evidence</StatusPill>
                  <span className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => handleStartEdit(item)}
                      className="font-semibold text-forest-700 hover:text-forest-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
                    >
                      Edit
                    </button>
                    {onDelete && (
                      <button
                        type="button"
                        onClick={() => onDelete(item.id)}
                        className="font-semibold text-red-600 hover:text-red-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-600"
                      >
                        Delete
                      </button>
                    )}
                  </span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </SectionCard>
  );
}
