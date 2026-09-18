import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { Profile } from '../types';

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

  const fetchProfile = async () => {
    setLoading(true); setError(null);
    try {
      const response = await apiFetch<any>('/profile');
      setProfile(normalizeProfile(response.profile ?? response));
      setEvidenceCount((response.evidence ?? []).length);
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to load Master Profile'); }
    finally { setLoading(false); }
  };
  useEffect(() => { fetchProfile(); }, []);

  const saveProfile = async (event: React.FormEvent) => {
    event.preventDefault(); if (!profile) return;
    try {
      const saved = await apiFetch<Profile>('/profile', { method: 'PUT', body: JSON.stringify({
        headline: profile.headline, phone: profile.phone, location: profile.location,
        professionalSummary: profile.professional_summary, links: profile.links ?? {},
        workEligibility: profile.work_eligibility, careerGoals: profile.career_goals,
      }) });
      setProfile(normalizeProfile(saved)); setMessage('Master Profile saved. Future jobs will reuse this verified evidence.');
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

  if (loading) return <div className="p-12 text-center text-slate-400 text-sm">Loading Master Profile...</div>;
  if (!profile) return <div className="max-w-3xl mx-auto p-8 rounded-xl bg-slate-800 border border-slate-700 text-slate-300">No Master Profile exists yet. Ask the account owner to initialize setup.</div>;

  const p = profile;
  return <div className="space-y-6 max-w-6xl mx-auto">
    <header className="flex flex-col md:flex-row md:items-center justify-between gap-4">
      <div><h1 className="text-2xl font-bold text-white">Master Profile / Master CV</h1><p className="text-sm text-slate-400">Enter career evidence once. Tailored CVs are immutable job-specific snapshots.</p><p className="text-xs text-emerald-400 mt-1">{evidenceCount} provenance-linked verified evidence claims</p></div>
      <div className="flex items-center gap-2"><span className={`px-3 py-1 rounded-full text-xs font-bold ${p.setup_status === 'READY' ? 'bg-emerald-500/20 text-emerald-400' : 'bg-amber-500/20 text-amber-400'}`}>{p.setup_status === 'READY' ? 'SETUP COMPLETE' : 'SETUP INCOMPLETE'}</span><span className="text-xs text-slate-500">revision {p.master_revision}</span><button onClick={() => setShowPurge(true)} className="px-3 py-2 rounded-lg text-xs text-red-400 border border-red-500/20">Purge data</button></div>
    </header>
    {message && <div className="p-3 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-300 text-xs">{message}</div>}
    {error && <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-300 text-xs">{error}</div>}

    <form onSubmit={saveProfile} className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
      <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">Canonical identity and summary</h2>
      <div className="grid md:grid-cols-3 gap-3">{(['headline', 'location', 'phone'] as const).map(key => <label key={key} className="text-xs text-slate-400">{key}<input value={(p[key] as string) ?? ''} onChange={e => setProfile({ ...p, [key]: e.target.value })} className="mt-1 w-full rounded-lg bg-slate-900 border border-slate-700 px-3 py-2 text-sm text-white" /></label>)}</div>
      <label className="text-xs text-slate-400 block">Professional summary<textarea value={p.professional_summary ?? ''} onChange={e => setProfile({ ...p, professional_summary: e.target.value })} rows={3} className="mt-1 w-full rounded-lg bg-slate-900 border border-slate-700 px-3 py-2 text-sm text-white" /></label>
      <button className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold">Save Master Profile</button>
    </form>

    <EvidenceSection title="Education / MSc and degrees" items={p.education} fields={['institution', 'qualification', 'field', 'startYear', 'endYear', 'grade']} values={newEducation} setValues={setNewEducation} onAdd={() => add('/profile/education', { ...newEducation, startYear: newEducation.startYear ? Number(newEducation.startYear) : null, endYear: newEducation.endYear ? Number(newEducation.endYear) : null }, () => setNewEducation({ institution: '', qualification: '', field: '', startYear: '', endYear: '', grade: '' }))} onUpdate={(id, values, reset) => update('/profile/education', id, { ...values, startYear: values.startYear ? Number(values.startYear) : null, endYear: values.endYear ? Number(values.endYear) : null }, reset)} onDelete={id => remove('/profile/education', id)} />
    <EvidenceSection title="Work experience" items={p.experiences} fields={['company', 'title', 'startMonth', 'endMonth', 'location', 'bullets']} values={newExperience} setValues={setNewExperience} onAdd={() => add('/profile/experiences', { ...newExperience, startMonth: newExperience.startMonth || null, endMonth: newExperience.endMonth || null, bullets: newExperience.bullets ? newExperience.bullets.split('\n').map(text => ({ text })) : [] }, () => setNewExperience({ company: '', title: '', startMonth: '', endMonth: '', location: '', bullets: '' }))} onUpdate={(id, values, reset) => update('/profile/experiences', id, { ...values, startMonth: values.startMonth || null, endMonth: values.endMonth || null, bullets: values.bullets ? values.bullets.split('\\n').map(text => ({ text })) : [] }, reset)} onDelete={id => remove('/profile/experiences', id)} />
    <EvidenceSection title="Projects" items={p.projects} fields={['name', 'summary', 'url']} values={newProject} setValues={setNewProject} onAdd={() => add('/profile/projects', { ...newProject, bullets: [] }, () => setNewProject({ name: '', summary: '', url: '' }))} onUpdate={(id, values, reset) => update('/profile/projects', id, { ...values, bullets: [] }, reset)} onDelete={id => remove('/profile/projects', id)} />
    <EvidenceSection title="Verified skills" items={p.skills} fields={['name', 'category', 'mastery', 'years']} values={newSkill} setValues={setNewSkill} onAdd={() => add('/profile/skills', { ...newSkill, mastery: Number(newSkill.mastery), years: newSkill.years ? Number(newSkill.years) : null }, () => setNewSkill({ name: '', category: '', mastery: '3', years: '' }))} onUpdate={(id, values, reset) => update('/profile/skills', id, { ...values, mastery: Number(values.mastery), years: values.years ? Number(values.years) : null }, reset)} onDelete={id => remove('/profile/skills', id)} />
    <EvidenceSection title="Certifications" items={p.certifications} fields={['name', 'issuer', 'issuedOn', 'credentialId']} values={newCertification} setValues={setNewCertification} onAdd={() => add('/profile/certifications', newCertification, () => setNewCertification({ name: '', issuer: '', issuedOn: '', credentialId: '' }))} onUpdate={(id, values, reset) => update('/profile/certifications', id, values, reset)} onDelete={id => remove('/profile/certifications', id)} />

    <div className="flex justify-end"><button onClick={completeSetup} className="px-5 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-sm font-semibold">Mark Master Profile complete</button></div>
    {showPurge && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4"><form onSubmit={purge} className="w-full max-w-md rounded-xl bg-slate-900 border border-red-500/30 p-6 space-y-4"><h2 className="text-lg font-bold text-red-400">Purge career data</h2><p className="text-xs text-slate-300">This removes the editable Master Profile. Historical tailored CV snapshots remain immutable.</p><input type="password" required value={purgePassword} onChange={e => setPurgePassword(e.target.value)} placeholder="Account password" className="w-full rounded-lg bg-slate-800 border border-slate-700 px-3 py-2 text-white" /><div className="flex justify-end gap-2"><button type="button" onClick={() => setShowPurge(false)} className="px-3 py-2 text-xs text-slate-300">Cancel</button><button className="px-3 py-2 rounded bg-red-600 text-xs text-white">Confirm purge</button></div></form></div>}
  </div>;
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

function EvidenceSection({ title, items, fields, values, setValues, onAdd, onUpdate, onDelete }: { title: string; items: any[]; fields: string[]; values: Record<string, string>; setValues: (v: any) => void; onAdd: () => void; onUpdate?: (id: string, values: Record<string, string>, reset: () => void) => void; onDelete?: (id: string) => void }) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const valueFor = (item: any, field: string) => {
    const snake = field.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`);
    if (field === 'bullets') return (item.bullets ?? []).map((bullet: any) => bullet.text ?? '').join('\\n');
    return String(item[field] ?? item[snake] ?? '');
  };
  const reset = () => { setEditingId(null); };
  return <section className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4"><div className="flex justify-between"><h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">{title}</h2><span className="text-xs text-slate-500">{items?.length ?? 0} reusable evidence records</span></div><div className="grid md:grid-cols-3 gap-2">{fields.map(field => <input key={field} placeholder={field} value={values[field] ?? ''} onChange={e => setValues({ ...values, [field]: e.target.value })} className="rounded-lg bg-slate-900 border border-slate-700 px-3 py-2 text-xs text-white" />)}<button type="button" onClick={() => editingId && onUpdate ? onUpdate(editingId, values, reset) : onAdd()} className="rounded-lg bg-slate-700 hover:bg-slate-600 px-3 py-2 text-xs text-white">{editingId ? 'Save edit' : 'Add verified record'}</button>{editingId && <button type="button" onClick={reset} className="rounded-lg border border-slate-700 px-3 py-2 text-xs text-slate-400">Cancel</button>}</div><div className="grid md:grid-cols-2 gap-2">{(items ?? []).map((item: any) => <div key={item.id} className="p-3 rounded-lg bg-slate-900 border border-slate-800 text-xs text-slate-300"><div className="font-semibold text-white">{item.name ?? item.title ?? item.qualification ?? item.company}</div><div>{item.summary ?? item.institution ?? item.issuer ?? item.category ?? ''}</div><div className="flex items-center justify-between mt-2"><span className="text-emerald-400 text-[10px]">USER_VERIFIED evidence</span><span className="flex gap-2"><button type="button" onClick={() => { setEditingId(item.id); setValues(Object.fromEntries(fields.map(field => [field, valueFor(item, field)]))); }} className="text-indigo-400 hover:text-indigo-300">Edit</button>{onDelete && <button type="button" onClick={() => onDelete(item.id)} className="text-red-400 hover:text-red-300">Delete</button>}</span></div></div>)}</div></section>;
}
