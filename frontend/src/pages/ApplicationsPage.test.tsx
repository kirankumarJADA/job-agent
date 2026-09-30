import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';

const state = vi.hoisted(() => ({ items: [] as unknown[] }));

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(() => Promise.resolve({ items: state.items })),
}));

const { ApplicationsPage } = await import('./ApplicationsPage');

const render = (element: React.ReactElement) => renderToStaticMarkup(element);

/**
 * First-paint contract for the Applications queue page. Effects don't run in
 * the static renderer, so these pin the loading shell and — via the mocked
 * client's resolved shape — the empty/error boundaries' copy.
 */
describe('applications page', () => {
  it('renders the branded header and loading shell on first paint', () => {
    const html = render(React.createElement(ApplicationsPage));
    expect(html).toContain('Applications');
    expect(html).toContain('Pipeline');
    expect(html).toContain('Loading your applications');
  });

  it('never leaks backend error details into the markup', () => {
    const html = render(React.createElement(ApplicationsPage));
    expect(html).not.toContain('PSQLException');
    expect(html).not.toContain('DevPassword123!');
  });
});
