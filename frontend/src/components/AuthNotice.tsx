import React from 'react';

/**
 * Notice banner for the authentication forms.
 *
 * `role="alert"` on errors so a failed sign-in is announced immediately rather
 * than only being visible; `role="status"` on success, which is polite and does
 * not interrupt.
 */

interface AuthNoticeProps {
  tone: 'error' | 'success' | 'info';
  children: React.ReactNode;
}

const TONE_CLASSES: Record<AuthNoticeProps['tone'], string> = {
  error: 'border-red-500/25 bg-red-500/10 text-red-300',
  success: 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300',
  info: 'border-indigo-500/25 bg-indigo-500/10 text-indigo-200',
};

export const AuthNotice: React.FC<AuthNoticeProps> = ({ tone, children }) => (
  <div
    role={tone === 'error' ? 'alert' : 'status'}
    className={`mb-4 rounded-lg border px-3.5 py-3 text-sm leading-relaxed ${TONE_CLASSES[tone]}`}
  >
    {children}
  </div>
);
