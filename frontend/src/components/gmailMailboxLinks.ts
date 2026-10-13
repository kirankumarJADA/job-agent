/** Builds the OAuth start URL from the configured API base, preserving its /api/v1 prefix. */
export function gmailAuthorizeUrl(apiBase: string): string {
  return `${apiBase.replace(/\/+$/, '')}/integrations/gmail/authorize`;
}
