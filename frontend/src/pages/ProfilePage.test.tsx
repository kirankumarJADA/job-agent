// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiFetch } from '../api/client';
import { ProfilePage, formatDateForInput, parseDateInput } from './ProfilePage';

afterEach(() => cleanup());

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(),
  setIdTokenProvider: vi.fn(),
  API_BASE: 'http://test/api/v1',
}));

const mockedApiFetch = vi.mocked(apiFetch);

const SAMPLE_PROFILE = {
  profile: {
    id: 'prof-1',
    user_id: 'user-1',
    headline: 'Senior Cloud Architect',
    location: 'London',
    phone: '+44 7700 900077',
    professional_summary: 'Cloud infrastructure expert.',
    work_eligibility: { right_to_work_uk: true, visa_status: 'Citizen' },
    career_goals: { summary: 'Architectural leadership' },
    master_revision: 2,
    setup_status: 'READY',
  },
  evidence: [],
  experiences: [
    {
      id: 'exp-year-1',
      profile_id: 'prof-1',
      company: 'Year Precision Corp',
      title: 'Principal Architect',
      startMonth: '2021-01-01',
      endMonth: '2023-01-01',
      startDatePrecision: 'YEAR',
      endDatePrecision: 'YEAR',
      location: 'Glasgow',
      bullets: [{ text: 'Designed multicloud landing zone' }],
      sortOrder: 1,
    },
    {
      id: 'exp-month-1',
      profile_id: 'prof-1',
      company: 'Month Precision Ltd',
      title: 'Lead Engineer',
      startMonth: '2019-05-01',
      endMonth: '2020-11-01',
      startDatePrecision: 'MONTH',
      endDatePrecision: 'MONTH',
      location: 'Belfast',
      bullets: [{ text: 'Built payment pipeline' }],
      sortOrder: 2,
    },
  ],
  education: [],
  projects: [],
  skills: [],
  certifications: [
    {
      id: 'cert-year-1',
      profile_id: 'prof-1',
      name: 'Terraform Associate',
      issuer: 'HashiCorp',
      issuedOn: '2022-01-01',
      issuedOnPrecision: 'YEAR',
      credentialId: 'HC-2022',
    },
    {
      id: 'cert-month-1',
      profile_id: 'prof-1',
      name: 'AWS Solutions Architect',
      issuer: 'Amazon Web Services',
      issuedOn: '2023-04-01',
      issuedOnPrecision: 'MONTH',
      credentialId: 'AWS-2023',
    },
  ],
};

describe('Date precision formatting and parsing helpers', () => {
  it('formats YEAR precision dates as YYYY without inventing month or day', () => {
    expect(formatDateForInput('2021-01-01', 'YEAR')).toBe('2021');
    expect(formatDateForInput('2021', 'YEAR')).toBe('2021');
  });

  it('formats MONTH precision dates as YYYY-MM without inventing day', () => {
    expect(formatDateForInput('2021-05-01', 'MONTH')).toBe('2021-05');
    expect(formatDateForInput('2021-05', 'MONTH')).toBe('2021-05');
  });

  it('formats dates without explicit precision as YYYY-MM when ISO string supplied', () => {
    expect(formatDateForInput('2021-05-01')).toBe('2021-05');
    expect(formatDateForInput('')).toBe('');
    expect(formatDateForInput(null)).toBe('');
  });

  it('parses YYYY input to YEAR precision and first day of year for storage', () => {
    expect(parseDateInput('2021')).toEqual({ date: '2021-01-01', precision: 'YEAR' });
  });

  it('parses YYYY-MM input to MONTH precision and first day of month for storage', () => {
    expect(parseDateInput('2021-05')).toEqual({ date: '2021-05-01', precision: 'MONTH' });
  });

  it('parses empty or whitespace input to null date and null precision', () => {
    expect(parseDateInput('')).toEqual({ date: null, precision: null });
    expect(parseDateInput('   ')).toEqual({ date: null, precision: null });
    expect(parseDateInput(null)).toEqual({ date: null, precision: null });
  });

  it('retains existing precision when editing full date representation', () => {
    expect(parseDateInput('2021-01-01', 'YEAR')).toEqual({ date: '2021-01-01', precision: 'YEAR' });
    expect(parseDateInput('2021-05-01', 'MONTH')).toEqual({ date: '2021-05-01', precision: 'MONTH' });
  });
});

describe('ProfilePage date precision editing and persistence', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedApiFetch.mockImplementation(async (path: string) => {
      if (path === '/profile') {
        return SAMPLE_PROFILE;
      }
      return {};
    });
  });

  it('displays year-only dates as YYYY and month-level dates as YYYY-MM in card badges', async () => {
    render(React.createElement(ProfilePage));
    await waitFor(() => {
      expect(screen.getByText(/Year Precision Corp/)).toBeTruthy();
    });

    expect(screen.getByText('2021 – 2023')).toBeTruthy();
    expect(screen.getByText('2019-05 – 2020-11')).toBeTruthy();
    expect(screen.getByText('Issued 2022')).toBeTruthy();
    expect(screen.getByText('Issued 2023-04')).toBeTruthy();
  });

  it('populates editor inputs as YYYY for YEAR precision experience and submits YEAR precision on save', async () => {
    render(React.createElement(ProfilePage));
    await waitFor(() => {
      expect(screen.getByText(/Year Precision Corp/)).toBeTruthy();
    });

    // Find and click the Edit button for the Year Precision Corp card
    const editButtons = screen.getAllByRole('button', { name: 'Edit' });
    // First edit button corresponds to Year Precision Corp
    await userEvent.click(editButtons[0]);

    // Check that startMonth input displays '2021' not '2021-01-01'
    const startInput = screen.getByLabelText('Work experience — new record startMonth') as HTMLInputElement;
    const endInput = screen.getByLabelText('Work experience — new record endMonth') as HTMLInputElement;
    expect(startInput.value).toBe('2021');
    expect(endInput.value).toBe('2023');

    // Edit an unrelated field, e.g. location
    const locationInput = screen.getByLabelText('Work experience — new record location') as HTMLInputElement;
    await userEvent.clear(locationInput);
    await userEvent.type(locationInput, 'Edinburgh');

    // Click Save edit
    const saveButton = screen.getByRole('button', { name: 'Save edit' });
    await userEvent.click(saveButton);

    // Verify PUT request payload includes startDatePrecision: 'YEAR' and startMonth: '2021-01-01'
    await waitFor(() => {
      const putCall = mockedApiFetch.mock.calls.find(
        ([path, opts]) => path === '/profile/experiences/exp-year-1' && opts?.method === 'PUT'
      );
      expect(putCall).toBeDefined();
      const body = JSON.parse(String(putCall![1]!.body));
      expect(body.startMonth).toBe('2021-01-01');
      expect(body.endMonth).toBe('2023-01-01');
      expect(body.startDatePrecision).toBe('YEAR');
      expect(body.endDatePrecision).toBe('YEAR');
      expect(body.location).toBe('Edinburgh');
    });
  });

  it('populates editor inputs as YYYY-MM for MONTH precision experience and submits MONTH precision on save', async () => {
    render(React.createElement(ProfilePage));
    await waitFor(() => {
      expect(screen.getByText(/Month Precision Ltd/)).toBeTruthy();
    });

    const editButtons = screen.getAllByRole('button', { name: 'Edit' });
    // Second edit button is Month Precision Ltd
    await userEvent.click(editButtons[1]);

    const startInput = screen.getByLabelText('Work experience — new record startMonth') as HTMLInputElement;
    const endInput = screen.getByLabelText('Work experience — new record endMonth') as HTMLInputElement;
    expect(startInput.value).toBe('2019-05');
    expect(endInput.value).toBe('2020-11');

    const saveButton = screen.getByRole('button', { name: 'Save edit' });
    await userEvent.click(saveButton);

    await waitFor(() => {
      const putCall = mockedApiFetch.mock.calls.find(
        ([path, opts]) => path === '/profile/experiences/exp-month-1' && opts?.method === 'PUT'
      );
      expect(putCall).toBeDefined();
      const body = JSON.parse(String(putCall![1]!.body));
      expect(body.startMonth).toBe('2019-05-01');
      expect(body.endMonth).toBe('2020-11-01');
      expect(body.startDatePrecision).toBe('MONTH');
      expect(body.endDatePrecision).toBe('MONTH');
    });
  });

  it('retains and submits YEAR precision when editing a certification', async () => {
    render(React.createElement(ProfilePage));
    await waitFor(() => {
      expect(screen.getByText('Terraform Associate')).toBeTruthy();
    });

    // In the certifications section, edit button index 2 is Terraform Associate
    const editButtons = screen.getAllByRole('button', { name: 'Edit' });
    await userEvent.click(editButtons[2]);

    const issuedOnInput = screen.getByLabelText('Certifications — new record issuedOn') as HTMLInputElement;
    expect(issuedOnInput.value).toBe('2022');

    // Save edit
    const saveButton = screen.getByRole('button', { name: 'Save edit' });
    await userEvent.click(saveButton);

    await waitFor(() => {
      const putCall = mockedApiFetch.mock.calls.find(
        ([path, opts]) => path === '/profile/certifications/cert-year-1' && opts?.method === 'PUT'
      );
      expect(putCall).toBeDefined();
      const body = JSON.parse(String(putCall![1]!.body));
      expect(body.issuedOn).toBe('2022-01-01');
      expect(body.issuedOnPrecision).toBe('YEAR');
    });
  });

  it('saves, lists without exposing passwords, and disables employer portal credentials', async () => {
    let savedList: Array<{ employerDomain: string; username: string; status: string }> = [];
    mockedApiFetch.mockImplementation(async (path: string, opts?: RequestInit) => {
      if (path === '/profile') return SAMPLE_PROFILE;
      if (path === '/employer-credentials' && (!opts?.method || opts.method === 'GET')) {
        return { credentials: savedList };
      }
      if (path === '/employer-credentials' && opts?.method === 'POST') {
        const body = JSON.parse(String(opts.body));
        savedList = [{ employerDomain: body.employerDomain, username: body.username, status: 'ACTIVE' }];
        return { employerDomain: body.employerDomain, saved: true };
      }
      if (path === '/employer-credentials/careers.acme.com' && opts?.method === 'DELETE') {
        savedList = [];
        return { employerDomain: 'careers.acme.com', disabled: true };
      }
      return {};
    });

    const user = userEvent.setup();
    render(React.createElement(ProfilePage));
    await waitFor(() => {
      expect(screen.getByLabelText('Employer domain')).toBeTruthy();
    });

    await user.type(screen.getByLabelText('Employer domain'), 'careers.acme.com');
    await user.type(screen.getByLabelText('Employer username'), 'ada@inbox.example');
    await user.type(screen.getByLabelText('Employer password'), 'TopSecret!Pass99');
    await user.click(screen.getByRole('button', { name: /Save credential/ }));

    expect(await screen.findByText('careers.acme.com')).toBeTruthy();
    expect(screen.getByText(/user: ada@inbox\.example/)).toBeTruthy();
    expect((screen.getByLabelText('Employer password') as HTMLInputElement).value).toBe('');
    expect(document.body.textContent?.includes('TopSecret!Pass99')).toBe(false);

    await user.click(screen.getByRole('button', { name: 'Remove' }));
    await waitFor(() => {
      expect(screen.queryByText('careers.acme.com')).toBeNull();
    });
  });
});
