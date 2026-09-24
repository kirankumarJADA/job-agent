import { describe, expect, it } from 'vitest';

import {
  MissingFirebaseConfigurationError,
  REQUIRED_FIREBASE_KEYS,
  readFirebaseConfiguration,
} from './config';

const COMPLETE = {
  VITE_FIREBASE_API_KEY: 'AIzaSyExample',
  VITE_FIREBASE_AUTH_DOMAIN: 'robin.firebaseapp.com',
  VITE_FIREBASE_PROJECT_ID: 'robin-project',
  VITE_FIREBASE_STORAGE_BUCKET: 'robin-project.appspot.com',
  VITE_FIREBASE_MESSAGING_SENDER_ID: '1234567890',
  VITE_FIREBASE_APP_ID: '1:1234567890:web:abcdef',
};

describe('firebase configuration', () => {
  it('reports every variable when nothing is set', () => {
    const result = readFirebaseConfiguration({});

    expect(result.isConfigured).toBe(false);
    expect(result.config).toBeNull();
    expect(result.missingKeys).toEqual(REQUIRED_FIREBASE_KEYS.map((entry) => entry.envVar));
    expect(result.missingKeys).toHaveLength(6);
  });

  it('names exactly the variables a partial configuration is missing', () => {
    const { VITE_FIREBASE_MESSAGING_SENDER_ID: _omitted, ...partial } = COMPLETE;

    const result = readFirebaseConfiguration(partial);

    expect(result.isConfigured).toBe(false);
    expect(result.config).toBeNull();
    expect(result.missingKeys).toEqual(['VITE_FIREBASE_MESSAGING_SENDER_ID']);
  });

  it('treats blank and whitespace-only values as missing rather than as present', () => {
    const result = readFirebaseConfiguration({
      ...COMPLETE,
      VITE_FIREBASE_API_KEY: '   ',
      VITE_FIREBASE_APP_ID: '',
    });

    expect(result.isConfigured).toBe(false);
    expect(result.missingKeys).toEqual(['VITE_FIREBASE_API_KEY', 'VITE_FIREBASE_APP_ID']);
  });

  it('ignores non-string values instead of coercing them', () => {
    const result = readFirebaseConfiguration({ ...COMPLETE, VITE_FIREBASE_PROJECT_ID: 42 });

    expect(result.isConfigured).toBe(false);
    expect(result.missingKeys).toEqual(['VITE_FIREBASE_PROJECT_ID']);
  });

  it('produces a complete config object when everything is set', () => {
    const result = readFirebaseConfiguration(COMPLETE);

    expect(result.isConfigured).toBe(true);
    expect(result.missingKeys).toEqual([]);
    expect(result.config).toEqual({
      apiKey: COMPLETE.VITE_FIREBASE_API_KEY,
      authDomain: COMPLETE.VITE_FIREBASE_AUTH_DOMAIN,
      projectId: COMPLETE.VITE_FIREBASE_PROJECT_ID,
      storageBucket: COMPLETE.VITE_FIREBASE_STORAGE_BUCKET,
      messagingSenderId: COMPLETE.VITE_FIREBASE_MESSAGING_SENDER_ID,
      appId: COMPLETE.VITE_FIREBASE_APP_ID,
    });
  });

  it('trims surrounding whitespace from supplied values', () => {
    const result = readFirebaseConfiguration({
      ...COMPLETE,
      VITE_FIREBASE_PROJECT_ID: '  robin-project  ',
    });

    expect(result.config?.projectId).toBe('robin-project');
  });

  it('never returns a partial config object alongside a failure', () => {
    // A half-applied configuration is worse than none: initializeApp would fail
    // far from the real problem.
    const result = readFirebaseConfiguration({ VITE_FIREBASE_API_KEY: 'AIzaSyExample' });

    expect(result.config).toBeNull();
    expect(result.isConfigured).toBe(false);
  });

  it('errors name the missing variables so the message is actionable', () => {
    const error = new MissingFirebaseConfigurationError([
      'VITE_FIREBASE_API_KEY',
      'VITE_FIREBASE_APP_ID',
    ]);

    expect(error.missingKeys).toEqual(['VITE_FIREBASE_API_KEY', 'VITE_FIREBASE_APP_ID']);
    expect(error.message).toContain('VITE_FIREBASE_API_KEY');
    expect(error.name).toBe('MissingFirebaseConfigurationError');
  });

  it('never carries a server-side secret variable', () => {
    // Belt and braces: a VITE_-prefixed service-account key would be shipped to
    // the browser. The frontend must only ever read the six public web values.
    for (const { envVar } of REQUIRED_FIREBASE_KEYS) {
      expect(envVar).toMatch(/^VITE_FIREBASE_/);
      expect(envVar).not.toContain('PRIVATE_KEY');
      expect(envVar).not.toContain('CLIENT_EMAIL');
    }
  });
});
