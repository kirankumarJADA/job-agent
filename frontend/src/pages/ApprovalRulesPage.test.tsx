// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ApprovalRulesPage } from './ApprovalRulesPage';
import { apiFetch } from '../api/client';

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

const enabledRule = {
  autoApproveEnabled: true,
  minScore: 85,
  configured: true,
};

const disabledRule = {
  autoApproveEnabled: false,
  minScore: 85,
  configured: true,
};

const unconfiguredRule = {
  autoApproveEnabled: false,
  minScore: 85,
  configured: false,
};

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

    // Loading state shown first
    expect(screen.getByText('Loading approval rules…')).toBeTruthy();

    // After load, the page renders with rule values
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());
    expect(screen.getByText('Enabled')).toBeTruthy();

    // Score is displayed
    const scoreInput = screen.getByLabelText('Minimum score input') as HTMLInputElement;
    expect(scoreInput.value).toBe('85');

    // Decision mode explainer cards are present
    expect(screen.getByText('Manual')).toBeTruthy();
    expect(screen.getByText('Assisted')).toBeTruthy();
    expect(screen.getByText('Controlled Auto')).toBeTruthy();

    // Safety notice is present
    expect(screen.getByText('Safety guarantees')).toBeTruthy();
  });

  // ── 2. Editing: toggle and score changes update form state ──
  it('allows toggling auto-approval and changing the score', async () => {
    mockedApiFetch.mockResolvedValueOnce(enabledRule);
    renderPage();

    await waitFor(() => expect(screen.getByText('Enabled')).toBeTruthy());

    // Toggle auto-approval off
    const toggle = screen.getByRole('switch');
    await userEvent.click(toggle);
    expect(screen.getByText('Disabled')).toBeTruthy();

    // Change score via the number input
    const scoreInput = screen.getByLabelText('Minimum score input') as HTMLInputElement;
    await userEvent.clear(scoreInput);
    await userEvent.type(scoreInput, '90');
    expect(scoreInput.value).toBe('90');
  });

  // ── 3. Saving: calls PUT and shows success message ──
  it('saves the rule and shows a success message', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(enabledRule) // GET
      .mockResolvedValueOnce({ autoApproveEnabled: true, minScore: 85 }); // PUT

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

  // ── 4. Validation: rejects invalid score values ──
  it('shows a validation error for out-of-range scores', async () => {
    mockedApiFetch.mockResolvedValueOnce(enabledRule);
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    const scoreInput = screen.getByLabelText('Minimum score input') as HTMLInputElement;
    await userEvent.clear(scoreInput);
    await userEvent.type(scoreInput, '0');

    // Validation message appears
    expect(screen.getByText('Score must be a whole number between 1 and 100.')).toBeTruthy();

    // Save button is disabled
    expect(saveButton().disabled).toBe(true);
  });

  // ── 5. Unconfigured state is distinct from a disabled rule ──
  it('distinguishes an unconfigured rule from a disabled one', async () => {
    mockedApiFetch.mockResolvedValueOnce(unconfiguredRule);
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    // A distinct status, not "Disabled"
    expect(screen.getByText('Not configured')).toBeTruthy();
    expect(screen.queryByText('Disabled')).toBeNull();

    // Explains the effective behaviour per mode
    expect(screen.getByText('No custom rule is saved yet.')).toBeTruthy();
    expect(screen.getByText(/requires an enabled rule/)).toBeTruthy();
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
    mockedApiFetch.mockResolvedValueOnce(unconfiguredRule);
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    // Save is disabled and explains why
    expect(saveButton().disabled).toBe(true);
    expect(screen.getByText(/Adjust a setting to save a rule/)).toBeTruthy();

    // Clicking cannot issue a PUT (only the initial GET happened)
    await userEvent.click(saveButton());
    expect(mockedApiFetch).toHaveBeenCalledTimes(1);
    expect(mockedApiFetch).toHaveBeenCalledWith('/approval-rules');
  });

  it('saves only after the owner makes an explicit choice', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(unconfiguredRule) // GET
      .mockResolvedValueOnce({ autoApproveEnabled: true, minScore: 85 }); // PUT

    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    // Make a deliberate choice: enable the rule
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

  it('allows an explicit disabled rule once the owner has touched the form', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(unconfiguredRule) // GET
      .mockResolvedValueOnce({ autoApproveEnabled: false, minScore: 90 }); // PUT

    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    const scoreInput = screen.getByLabelText('Minimum score input') as HTMLInputElement;
    await userEvent.clear(scoreInput);
    await userEvent.type(scoreInput, '90');

    await userEvent.click(saveButton());

    await waitFor(() =>
      expect(mockedApiFetch).toHaveBeenCalledWith('/approval-rules', {
        method: 'PUT',
        body: JSON.stringify({ autoApproveEnabled: false, minScore: 90 }),
      }),
    );
  });

  // ── 7. Mode explainer reflects the fail-closed backend behaviour ──
  it('describes Controlled Auto as requiring an enabled rule', async () => {
    mockedApiFetch.mockResolvedValueOnce(enabledRule);
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    expect(screen.getByText('Needs enabled rule')).toBeTruthy();
    expect(
      screen.getByText(/Matches auto-approve only while your rule is enabled/),
    ).toBeTruthy();
    // Enabling a rule never overrides quotas or safety checks
    expect(screen.getByText(/Enabling a rule never overrides a safety check/)).toBeTruthy();
  });

  // ── 8. Error handling ──
  it('shows an error when the API call fails', async () => {
    mockedApiFetch.mockRejectedValueOnce(new Error('Network error'));
    renderPage();

    await waitFor(() =>
      expect(screen.getByText('Failed to load approval rules.')).toBeTruthy(),
    );
  });

  it('shows an error when saving fails', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(enabledRule) // GET
      .mockRejectedValueOnce(new Error('min_score must be between 1 and 100')); // PUT

    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    await userEvent.click(saveButton());

    await waitFor(() =>
      expect(screen.getByText('min_score must be between 1 and 100')).toBeTruthy(),
    );
  });
});
