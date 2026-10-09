// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ApprovalRulesPage } from './ApprovalRulesPage';
import { ApiError, apiFetch } from '../api/client';

vi.mock('../api/client', () => ({
  ApiError: class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  },
  apiFetch: vi.fn(),
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedApiFetch = vi.mocked(apiFetch);

/** A readable rule body as GET returns it for the CONFIGURED / ABSENT states. */
const readableBody = (over: Partial<Record<string, unknown>> = {}) => ({
  availability: 'CONFIGURED',
  configured: true,
  autoApproveEnabled: true,
  minScore: 85,
  applicationMode: 'ASSISTED',
  assistedFloor: 85,
  ...over,
});

const enabledRule = readableBody();
const disabledRule = readableBody({ autoApproveEnabled: false });
const absentRule = readableBody({
  availability: 'ABSENT',
  configured: false,
  autoApproveEnabled: false,
});

function renderPage() {
  return render(
    React.createElement(MemoryRouter, null, React.createElement(ApprovalRulesPage)),
  );
}

function saveButton(): HTMLButtonElement {
  return screen.getByText('Save approval rules') as HTMLButtonElement;
}

describe('ApprovalRulesPage', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ── 1. Loading: renders current rule values ──
  it('loads and displays the current approval rule', async () => {
    mockedApiFetch.mockResolvedValueOnce(enabledRule);
    renderPage();

    expect(screen.getByText('Loading approval rules…')).toBeTruthy();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());
    expect(screen.getByText('Enabled')).toBeTruthy();

    const scoreInput = screen.getByLabelText('Minimum score input') as HTMLInputElement;
    expect(scoreInput.value).toBe('85');

    expect(screen.getByText('Manual')).toBeTruthy();
    expect(screen.getByText('Assisted')).toBeTruthy();
    expect(screen.getByText('Controlled Auto')).toBeTruthy();
    expect(screen.getByText('Safety guarantees')).toBeTruthy();
  });

  // ── 2. Editing ──
  it('allows toggling auto-approval and changing the score', async () => {
    mockedApiFetch.mockResolvedValueOnce(enabledRule);
    renderPage();

    await waitFor(() => expect(screen.getByText('Enabled')).toBeTruthy());

    await userEvent.click(screen.getByRole('switch'));
    expect(screen.getByText('Disabled')).toBeTruthy();

    const scoreInput = screen.getByLabelText('Minimum score input') as HTMLInputElement;
    await userEvent.clear(scoreInput);
    await userEvent.type(scoreInput, '90');
    expect(scoreInput.value).toBe('90');
  });

  // ── 3. Saving ──
  it('saves the rule and shows a success message', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(enabledRule) // GET
      .mockResolvedValueOnce(readableBody()); // PUT

    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    await userEvent.click(saveButton());

    await waitFor(() =>
      expect(mockedApiFetch).toHaveBeenCalledWith('/approval-rules', {
        method: 'PUT',
        body: JSON.stringify({ autoApproveEnabled: true, minScore: 85 }),
      }),
    );

    await waitFor(() =>
      expect(screen.getByText('Approval rules saved. Audit log recorded.')).toBeTruthy(),
    );
  });

  // ── 4. Validation ──
  it('shows a validation error for out-of-range scores', async () => {
    mockedApiFetch.mockResolvedValueOnce(enabledRule);
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    const scoreInput = screen.getByLabelText('Minimum score input') as HTMLInputElement;
    await userEvent.clear(scoreInput);
    await userEvent.type(scoreInput, '0');

    expect(screen.getByText('Score must be a whole number between 1 and 100.')).toBeTruthy();
    expect(saveButton().disabled).toBe(true);
  });

  // ── 5. ABSENT is distinct from DISABLED ──
  it('distinguishes an unconfigured rule from a disabled one', async () => {
    mockedApiFetch.mockResolvedValueOnce(absentRule);
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    expect(screen.getByText('Not configured')).toBeTruthy();
    expect(screen.queryByText('Disabled')).toBeNull();
    expect(screen.getByText('No custom rule is saved yet.')).toBeTruthy();
  });

  it('marks a saved-but-off rule as disabled, not unconfigured', async () => {
    mockedApiFetch.mockResolvedValueOnce(disabledRule);
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    expect(screen.getByText('Disabled')).toBeTruthy();
    expect(screen.queryByText('Not configured')).toBeNull();
    expect(screen.getByText(/by your saved rule/)).toBeTruthy();
  });

  // ── 6. An untouched unconfigured form cannot save a disabled rule ──
  it('blocks saving an untouched unconfigured form', async () => {
    mockedApiFetch.mockResolvedValueOnce(absentRule);
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    expect(saveButton().disabled).toBe(true);
    expect(screen.getByText(/Adjust a setting to save a rule/)).toBeTruthy();

    await userEvent.click(saveButton());
    expect(mockedApiFetch).toHaveBeenCalledTimes(1);
    expect(mockedApiFetch).toHaveBeenCalledWith('/approval-rules');
  });

  it('saves only after the owner makes an explicit choice', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(absentRule) // GET
      .mockResolvedValueOnce(readableBody()); // PUT

    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    await userEvent.click(screen.getByRole('switch'));
    expect(saveButton().disabled).toBe(false);

    await userEvent.click(saveButton());

    await waitFor(() =>
      expect(mockedApiFetch).toHaveBeenCalledWith('/approval-rules', {
        method: 'PUT',
        body: JSON.stringify({ autoApproveEnabled: true, minScore: 85 }),
      }),
    );
  });

  // ── 7. The absent-state explanation follows the real decision mode ──
  it('explains the actual default behaviour for the current mode', async () => {
    mockedApiFetch.mockResolvedValueOnce(
      readableBody({ availability: 'ABSENT', configured: false, autoApproveEnabled: false, applicationMode: 'CONTROLLED_AUTO' }),
    );
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    expect(screen.getByText(/Controlled Auto needs an enabled rule/)).toBeTruthy();
    expect(screen.getByText('Needs enabled rule')).toBeTruthy();
    // The current mode is called out rather than leaving the owner to guess.
    expect(screen.getByText('current')).toBeTruthy();
  });

  it('uses the backend floor in the Assisted explanation when no rule is saved', async () => {
    mockedApiFetch.mockResolvedValueOnce(
      readableBody({
        availability: 'ABSENT',
        configured: false,
        autoApproveEnabled: false,
        applicationMode: 'ASSISTED',
        assistedFloor: 91,
      }),
    );
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    expect(screen.getByText(/auto-approves matches scoring 91 or higher/)).toBeTruthy();
  });

  it('explains that Manual mode ignores the rule', async () => {
    mockedApiFetch.mockResolvedValueOnce(
      readableBody({ availability: 'ABSENT', configured: false, autoApproveEnabled: false, applicationMode: 'MANUAL' }),
    );
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    expect(screen.getByText(/Manual mode sends every match to the review queue/)).toBeTruthy();
  });

  // ── 8. UNREADABLE: an explicit failure state, never stale settings ──
  it('shows a warning and hides the form when the rule is unreadable', async () => {
    mockedApiFetch.mockRejectedValueOnce(
      new ApiError(503, 'Your approval rule could not be read, so automatic approval is paused for safety.'),
    );
    renderPage();

    await waitFor(() => expect(screen.getByText('Your approval rule could not be read')).toBeTruthy());

    // The safety consequence is stated plainly.
    expect(screen.getByText(/Automatic approval has been disabled for safety/)).toBeTruthy();
    expect(screen.getByText(/require human review/)).toBeTruthy();

    // No editable form and no score that could be mistaken for loaded state.
    expect(screen.queryByText('Save approval rules')).toBeNull();
    expect(screen.queryByLabelText('Minimum score input')).toBeNull();
    expect(screen.queryByRole('switch')).toBeNull();
  });

  it('offers a retry that reloads an unreadable rule', async () => {
    mockedApiFetch
      .mockRejectedValueOnce(new ApiError(503, 'Your approval rule could not be read.'))
      .mockResolvedValueOnce(enabledRule);

    renderPage();
    await waitFor(() => expect(screen.getByText('Your approval rule could not be read')).toBeTruthy());

    await userEvent.click(screen.getByText('Refresh'));

    await waitFor(() => expect(screen.getByText('Enabled')).toBeTruthy());
    expect(screen.queryByText('Your approval rule could not be read')).toBeNull();
    expect(mockedApiFetch).toHaveBeenCalledTimes(2);
  });

  // ── 9. A load failure is also unresolved: no form, no stale settings ──
  it('shows an error and hides the form when loading fails outright', async () => {
    mockedApiFetch.mockRejectedValueOnce(new Error('Network error'));
    renderPage();

    await waitFor(() => expect(screen.getByText(/Network error/)).toBeTruthy());

    expect(screen.queryByText('Save approval rules')).toBeNull();
    expect(screen.getByText('Refresh')).toBeTruthy();
  });

  it('does not report a generic outage as an unreadable rule', async () => {
    mockedApiFetch.mockRejectedValueOnce(new ApiError(500, 'Internal server error'));
    renderPage();

    await waitFor(() => expect(screen.getByText(/Internal server error/)).toBeTruthy());

    expect(screen.queryByText('Your approval rule could not be read')).toBeNull();
  });

  // ── 10. Safety copy covers the notification ──
  it('tells the owner they are notified when a rule cannot be read', async () => {
    mockedApiFetch.mockResolvedValueOnce(enabledRule);
    renderPage();
    await waitFor(() => expect(screen.getByText('Safety guarantees')).toBeTruthy());

    expect(screen.getByText(/every match falls back to human review and you are notified/)).toBeTruthy();
  });

  // ── 11. Save failure ──
  it('shows an error when saving fails', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(enabledRule)
      .mockRejectedValueOnce(new Error('min_score must be between 1 and 100'));

    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    await userEvent.click(saveButton());

    await waitFor(() =>
      expect(screen.getByText('min_score must be between 1 and 100')).toBeTruthy(),
    );
  });
});
