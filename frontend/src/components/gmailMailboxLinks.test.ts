import { describe, expect, it } from 'vitest';
import { gmailAuthorizeUrl } from './gmailMailboxLinks';

describe('gmailAuthorizeUrl', () => {
  it('preserves the API version path required by the backend controller', () => {
    expect(gmailAuthorizeUrl('https://job-agent-mwhu.onrender.com/api/v1'))
      .toBe('https://job-agent-mwhu.onrender.com/api/v1/integrations/gmail/authorize');
  });

  it('removes only trailing slashes without removing the API prefix', () => {
    expect(gmailAuthorizeUrl('http://localhost:8080/api/v1/'))
      .toBe('http://localhost:8080/api/v1/integrations/gmail/authorize');
  });
});
