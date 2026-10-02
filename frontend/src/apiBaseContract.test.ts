import { describe, expect, it } from 'vitest';
import { assertDeployableApiBase } from './apiBaseContract';

describe('assertDeployableApiBase', () => {
  it('accepts a base that ends with /api/v1', () => {
    expect(() =>
      assertDeployableApiBase({ VITE_API_BASE_URL: 'https://job-agent-mwhu.onrender.com/api/v1' }),
    ).not.toThrow();
  });

  it('accepts trailing slashes after /api/v1', () => {
    expect(() =>
      assertDeployableApiBase({ VITE_API_BASE_URL: 'https://api.example.com/api/v1///' }),
    ).not.toThrow();
  });

  it('accepts an unset base (client falls back to its localhost default)', () => {
    expect(() => assertDeployableApiBase({})).not.toThrow();
    expect(() => assertDeployableApiBase({ VITE_API_BASE_URL: '' })).not.toThrow();
  });

  it('rejects the exact misconfiguration that broke production', () => {
    expect(() =>
      assertDeployableApiBase({ VITE_API_BASE_URL: 'https://job-agent-mwhu.onrender.com' }),
    ).toThrowError(/must end with \/api\/v1/);
  });

  it('rejects any other missing suffix', () => {
    expect(() => assertDeployableApiBase({ VITE_API_BASE_URL: 'https://api.example.com/api' })).toThrowError();
    expect(() => assertDeployableApiBase({ VITE_API_BASE_URL: 'https://api.example.com/api/v2' })).toThrowError();
  });
});
