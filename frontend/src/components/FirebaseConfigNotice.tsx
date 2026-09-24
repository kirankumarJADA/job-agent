import React from 'react';

import { AuthNotice } from './AuthNotice';

/**
 * Shown in place of an auth form when the build has no Firebase configuration.
 *
 * This exists because the alternative — letting `initializeApp` fail on first
 * use — gives a blank screen or an opaque console error. Listing the exact
 * variables turns a dead end into a two-minute fix, and it is also the honest
 * answer: authentication genuinely cannot work until they are set.
 *
 * The empty-state text deliberately says "not configured for this build" rather
 * than "broken", because that is usually exactly what it is.
 */

interface FirebaseConfigNoticeProps {
  missingKeys: string[];
}

export const FirebaseConfigNotice: React.FC<FirebaseConfigNoticeProps> = ({ missingKeys }) => (
  <AuthNotice tone="error">
    <p className="font-semibold text-red-200">Firebase Authentication is not configured</p>
    <p className="mt-1.5">
      This deployment is missing the Firebase web configuration, so sign-in, sign-up and password
      reset are unavailable.
    </p>
    {missingKeys.length > 0 && (
      <>
        <p className="mt-3 text-xs font-semibold uppercase tracking-wider text-red-200/80">
          Set these environment variables and rebuild the frontend
        </p>
        <ul className="mt-1.5 space-y-0.5 font-mono text-xs text-red-200">
          {missingKeys.map((key) => (
            <li key={key}>{key}</li>
          ))}
        </ul>
      </>
    )}
    <p className="mt-3 text-xs text-red-200/80">
      They come from the Firebase console under Project settings &rarr; Your apps &rarr; Web app.
      These values are public identifiers, not secrets.
    </p>
  </AuthNotice>
);
