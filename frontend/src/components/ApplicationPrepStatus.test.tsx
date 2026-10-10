// @vitest-environment jsdom
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { ApplicationPrepStatus } from './ApplicationPrepStatus';

vi.mock('../api/client', () => ({ apiFetch: vi.fn() }));
const mocked = vi.mocked(apiFetch);

function renderStatus() {
  return render(React.createElement(MemoryRouter, null,
    React.createElement(ApplicationPrepStatus, { jobId: 'job-1', applicationId: 'app-1' })));
}

describe('ApplicationPrepStatus', () => {
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  it('reads readiness for that exact application and links to the PREP workspace', async () => {
    mocked.mockResolvedValue({
      overall: 'IN_PROGRESS', overallLabel: 'In progress', blockers: [],
      actions: [{ area: 'CV', code: 'CV_REVIEW', message: 'Review the CV' }],
    });
    renderStatus();
    expect(await screen.findByText('In progress')).toBeTruthy();
    expect(screen.getByText('1 action(s) remaining')).toBeTruthy();
    expect(mocked).toHaveBeenCalledWith('/prep/jobs/job-1/readiness?applicationId=app-1');
    expect(screen.getByRole('link', { name: /Open PREP workspace/ }).getAttribute('href')).toBe('/jobs/job-1#application-package');
  });

  it('shows unavailable rather than a status when the readiness call fails', async () => {
    mocked.mockRejectedValue(new Error('503'));
    renderStatus();
    expect(await screen.findByText('status unavailable')).toBeTruthy();
    expect(screen.queryByText('Ready for the next human-review step')).toBeNull();
  });
});
