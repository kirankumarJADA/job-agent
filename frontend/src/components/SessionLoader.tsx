import React from 'react';

/**
 * Shown while the application is still working out whether anyone is signed in.
 *
 * This state is deliberately brief but must exist: rendering the protected shell
 * before the session is known would flash the app for a signed-out visitor, and
 * redirecting to /login before the check completes would bounce a signed-in user
 * who simply reloaded the page.
 */
export const SessionLoader: React.FC<{ message?: string }> = ({
  message = 'Restoring your session…',
}) => (
  <div
    role="status"
    aria-live="polite"
    className="flex min-h-screen flex-col items-center justify-center gap-4 bg-cream-50"
  >
    <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-forest-900 text-lg font-bold text-cream-50 shadow-raise">
      R
    </div>
    <p className="text-xs tracking-wide text-ink-muted">{message}</p>
  </div>
);
