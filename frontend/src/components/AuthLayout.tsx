import React from 'react';

/**
 * Shared shell for every authentication screen: sign in, sign up and password
 * reset. Keeping the brand lockup and the surface treatment in one component is
 * what makes the three screens read as one product rather than three forms.
 *
 * Uses the same palette as the rest of the application (slate-950 base,
 * slate-800 borders, indigo accent) so the transition into the app after
 * signing in is visually continuous.
 */

interface AuthLayoutProps {
  /** Small label above the heading, e.g. "Sign in". */
  eyebrow: string;
  title: string;
  subtitle: string;
  children: React.ReactNode;
  /** Footer content, typically the link to the other auth screens. */
  footer?: React.ReactNode;
}

export const AuthLayout: React.FC<AuthLayoutProps> = ({
  eyebrow,
  title,
  subtitle,
  children,
  footer,
}) => (
  <div className="relative min-h-screen bg-slate-950 text-slate-100 antialiased flex flex-col">
    {/* Ambient background: two soft indigo glows over the slate base. Purely
        decorative, and aria-hidden so it stays out of the accessibility tree. */}
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 overflow-hidden">
      <div className="absolute -top-40 left-1/2 h-96 w-[36rem] -translate-x-1/2 rounded-full bg-indigo-600/20 blur-3xl" />
      <div className="absolute bottom-[-12rem] right-[-8rem] h-80 w-80 rounded-full bg-violet-600/10 blur-3xl" />
    </div>

    <header className="relative z-10 flex items-center justify-center gap-3 px-6 py-8">
      <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 text-lg font-bold text-white shadow-lg shadow-indigo-900/40">
        R
      </div>
      <div className="leading-tight">
        <p className="text-lg font-bold tracking-tight text-white">Robin</p>
        <p className="text-xs font-mono text-indigo-300/90">Your AI Job Agent</p>
      </div>
    </header>

    <main className="relative z-10 flex flex-1 items-start justify-center px-4 pb-16 sm:items-center sm:pb-24">
      <div className="w-full max-w-md">
        <div className="rounded-2xl border border-slate-800 bg-slate-900/80 p-6 shadow-2xl backdrop-blur-sm sm:p-8">
          <div className="mb-6">
            <p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-indigo-400">
              {eyebrow}
            </p>
            <h1 className="mt-2 text-2xl font-bold tracking-tight text-white sm:text-[1.75rem]">
              {title}
            </h1>
            <p className="mt-1.5 text-sm leading-relaxed text-slate-400">{subtitle}</p>
          </div>

          {children}
        </div>

        {footer && <div className="mt-6 text-center text-sm text-slate-400">{footer}</div>}
      </div>
    </main>
  </div>
);
