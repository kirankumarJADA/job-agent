import { describe, expect, it } from 'vitest';

import {
  PASSWORD_MIN_LENGTH,
  isValid,
  passwordProblems,
  validateConfirmPassword,
  validateEmail,
  validateForgotPassword,
  validateFullName,
  validateInviteCode,
  validatePassword,
  validateSignIn,
  validateSignUp,
} from './validation';

const validSignUp = {
  fullName: 'Alex Morgan',
  email: 'alex@example.com',
  password: 'correct1horse',
  confirmPassword: 'correct1horse',
  inviteCode: '',
};

describe('full name', () => {
  it('accepts a normal name', () => {
    expect(validateFullName('Alex Morgan')).toBeUndefined();
  });

  it('rejects an empty or whitespace-only name', () => {
    expect(validateFullName('')).toBeDefined();
    expect(validateFullName('   ')).toBeDefined();
  });

  it('rejects a single character', () => {
    expect(validateFullName('A')).toBeDefined();
  });

  it('ignores surrounding whitespace when measuring', () => {
    expect(validateFullName('  Alex  ')).toBeUndefined();
  });
});

describe('email', () => {
  it('accepts ordinary addresses', () => {
    for (const email of ['a@b.co', 'first.last+tag@sub.example.co.uk', '  padded@example.com  ']) {
      expect(validateEmail(email)).toBeUndefined();
    }
  });

  it('rejects malformed addresses', () => {
    for (const email of ['', 'plain', 'no@domain', '@example.com', 'a b@example.com', 'a@@b.com']) {
      expect(validateEmail(email)).toBeDefined();
    }
  });
});

describe('password', () => {
  it('accepts a password meeting the documented rules', () => {
    expect(validatePassword('correct1horse')).toBeUndefined();
    expect(passwordProblems('correct1horse')).toEqual([]);
  });

  it('requires a minimum length', () => {
    expect(validatePassword('short1')).toContain(String(PASSWORD_MIN_LENGTH));
  });

  it('requires a letter', () => {
    expect(passwordProblems('12345678')).toContain('Include at least one letter.');
  });

  it('requires a number', () => {
    expect(passwordProblems('abcdefgh')).toContain('Include at least one number.');
  });

  it('rejects an empty password with a single clear message', () => {
    expect(passwordProblems('')).toEqual(['Enter a password.']);
  });

  it('reports every problem at once so the checklist can be shown', () => {
    const problems = passwordProblems('abc');
    expect(problems).toHaveLength(2);
  });
});

describe('password confirmation', () => {
  it('accepts a matching confirmation', () => {
    expect(validateConfirmPassword('correct1horse', 'correct1horse')).toBeUndefined();
  });

  it('reports a mismatch', () => {
    expect(validateConfirmPassword('correct1horse', 'correct1horsf')).toMatch(/do not match/i);
  });

  it('reports an empty confirmation separately from a mismatch', () => {
    expect(validateConfirmPassword('correct1horse', '')).toMatch(/re-enter/i);
  });
});

describe('invite code', () => {
  it('is optional when the server does not require one', () => {
    expect(validateInviteCode('', false)).toBeUndefined();
  });

  it('is required when the server demands one', () => {
    expect(validateInviteCode('', true)).toBeDefined();
    expect(validateInviteCode('   ', true)).toBeDefined();
  });

  it('accepts a supplied code either way', () => {
    expect(validateInviteCode('abc123', true)).toBeUndefined();
    expect(validateInviteCode('abc123', false)).toBeUndefined();
  });
});

describe('sign-up form', () => {
  it('passes a fully valid submission', () => {
    expect(isValid(validateSignUp(validSignUp))).toBe(true);
  });

  it('reports each invalid field at once', () => {
    const errors = validateSignUp({
      fullName: '',
      email: 'nope',
      password: 'abc',
      confirmPassword: 'xyz',
      inviteCode: '',
    });

    expect(errors.fullName).toBeDefined();
    expect(errors.email).toBeDefined();
    expect(errors.password).toBeDefined();
    // The confirmation is only judged once the password itself is acceptable,
    // so a weak password does not also produce a misleading "do not match".
    expect(errors.confirmPassword).toBeUndefined();
  });

  it('reports a mismatch when the password is otherwise valid', () => {
    const errors = validateSignUp({ ...validSignUp, confirmPassword: 'different1' });
    expect(errors.confirmPassword).toMatch(/do not match/i);
    expect(errors.password).toBeUndefined();
  });

  it('demands an invite code only when the server says so', () => {
    expect(validateSignUp(validSignUp, false).inviteCode).toBeUndefined();
    expect(validateSignUp(validSignUp, true).inviteCode).toBeDefined();
    expect(isValid(validateSignUp({ ...validSignUp, inviteCode: 'secret' }, true))).toBe(true);
  });

  it('does not silently treat a missing invite code as valid when required', () => {
    const errors = validateSignUp({ ...validSignUp, inviteCode: '   ' }, true);
    expect(isValid(errors)).toBe(false);
  });
});

describe('sign-in form', () => {
  it('passes with a valid email and a non-empty password', () => {
    expect(isValid(validateSignIn({ email: 'alex@example.com', password: 'x' }))).toBe(true);
  });

  it('rejects a malformed email and an empty password', () => {
    const errors = validateSignIn({ email: 'nope', password: '' });
    expect(errors.email).toBeDefined();
    expect(errors.password).toBeDefined();
  });

  it('applies no length or complexity rule to an existing password', () => {
    // Existing passwords may predate any policy, so signing in must not reject
    // them locally.
    expect(validateSignIn({ email: 'alex@example.com', password: 'old' })).toEqual({});
  });
});

describe('forgot-password form', () => {
  it('requires a valid email', () => {
    expect(isValid(validateForgotPassword({ email: 'alex@example.com' }))).toBe(true);
    expect(isValid(validateForgotPassword({ email: '' }))).toBe(false);
    expect(isValid(validateForgotPassword({ email: 'nope' }))).toBe(false);
  });
});
