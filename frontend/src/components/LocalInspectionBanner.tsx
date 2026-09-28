import React from 'react';

import { API_BASE } from '../api/client';
import { LOCAL_INSPECTION_MARKER, currentLocalInspectionMode } from '../localInspection';

/**
 * A loud, unavoidable notice that the application is being inspected under LOCAL
 * INSPECTION MODE (see ../localInspection.ts). Its whole point is that the mode
 * can never be mistaken for a real sign-in.
 *
 * The `import.meta.env.DEV` test is part of a ternary at module scope on
 * purpose: Vite replaces it with the literal `false` in a production build, so
 * the false branch — this entire component body, marker text included — is
 * removed from the bundle rather than merely switched off at runtime.
 */
export const LocalInspectionBanner: React.FC =
  import.meta.env.DEV && currentLocalInspectionMode(API_BASE).enabled
    ? () => (
        <div
          role="status"
          data-testid="local-inspection-banner"
          className="mb-5 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-2.5 text-amber-200"
        >
          <span className="font-mono text-[11px] font-bold tracking-wider">
            {LOCAL_INSPECTION_MARKER}
          </span>
          <span className="text-xs text-amber-200/80">
            Development only. Signed in as the seeded local development account through the normal
            login endpoint — no Firebase sign-in, nothing faked. This banner cannot appear in a
            production build.
          </span>
        </div>
      )
    : () => null;
