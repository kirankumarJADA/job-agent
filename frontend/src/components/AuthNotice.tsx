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
  error: 'border-red-200 bg-red-50 text-red-800',
  success: 'border-emerald-200 bg-emerald-50 text-emerald-900',
  info: 'border-forest-200 bg-forest-50 text-forest-900',
};

export const AuthNotice: React.FC<AuthNoticeProps> = ({ tone, children }) => (
  <div
    role={tone === 'error' ? 'alert' : 'status'}
    className={`mb-4 rounded-lg border px-3.5 py-3 text-sm leading-relaxed ${TONE_CLASSES[tone]}`}
  >
    {children}
  </div>
);
