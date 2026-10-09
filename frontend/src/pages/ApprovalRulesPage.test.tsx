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

const defaultRule = {
  autoApproveEnabled: true,
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

describe('ApprovalRulesPage', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ── 1. Loading: renders current rule values ──
  it('loads and displays the current approval rule', async () => {
    mockedApiFetch.mockResolvedValueOnce(defaultRule);
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
    mockedApiFetch.mockResolvedValueOnce(defaultRule);
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
      .mockResolvedValueOnce(defaultRule) // GET
      .mockResolvedValueOnce({ autoApproveEnabled: true, minScore: 85 }); // PUT

    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    await userEvent.click(screen.getByText('Save approval rules'));

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
    mockedApiFetch.mockResolvedValueOnce(defaultRule);
    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    const scoreInput = screen.getByLabelText('Minimum score input') as HTMLInputElement;
    await userEvent.clear(scoreInput);
    await userEvent.type(scoreInput, '0');

    // Validation message appears
    expect(screen.getByText('Score must be a whole number between 1 and 100.')).toBeTruthy();

    // Save button is disabled
    const saveBtn = screen.getByText('Save approval rules');
    expect((saveBtn as HTMLButtonElement).disabled).toBe(true);
  });

  // ── 5. Unconfigured state: shows Phase 5 default notice ──
  it('shows a notice when no custom rule is configured', async () => {
    mockedApiFetch.mockResolvedValueOnce(unconfiguredRule);
    renderPage();

    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    // Shows disabled status
    expect(screen.getByText('Disabled')).toBeTruthy();

    // Shows the Phase 5 default notice
    expect(
      screen.getByText(/No custom rule configured yet/),
    ).toBeTruthy();
  });

  // ── 6. Error handling: shows error when API fails ──
  it('shows an error when the API call fails', async () => {
    mockedApiFetch.mockRejectedValueOnce(new Error('Network error'));
    renderPage();

    await waitFor(() =>
      expect(screen.getByText('Failed to load approval rules.')).toBeTruthy(),
    );
  });

  it('shows an error when saving fails', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(defaultRule) // GET
      .mockRejectedValueOnce(new Error('min_score must be between 1 and 100')); // PUT

    renderPage();
    await waitFor(() => expect(screen.getByText('Auto-Approval Rules')).toBeTruthy());

    await userEvent.click(screen.getByText('Save approval rules'));

    await waitFor(() =>
      expect(screen.getByText('min_score must be between 1 and 100')).toBeTruthy(),
    );
  });
});
