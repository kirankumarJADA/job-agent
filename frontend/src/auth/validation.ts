/**
 * Client-side form validation for the authentication screens.
 *
 * Deliberately pure functions with no React and no network: they are the only
 * place the rules live, so they can be tested exhaustively without a DOM, and
 * the same functions drive the inline field errors and the submit button's
 * disabled state. Server-side rules remain authoritative — this exists to give
 * fast, specific feedback, not to be a security boundary.
 */

export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 128;

/**
 * Deliberately modest and well-known rules. A strict composition policy pushes
 * people towards predictable substitutions without adding much real strength;
 * length does the heavy lifting.
 */
const HAS_LETTER = /[A-Za-z]/;
const HAS_DIGIT = /\d/;

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Per-field messages. A field absent from the object is valid. */
export type FieldErrors<T extends string> = Partial<Record<T, string>>;

export type SignUpField = 'fullName' | 'email' | 'password' | 'confirmPassword' | 'inviteCode';
export type SignInField = 'email' | 'password';
export type ForgotPasswordField = 'email';

export interface SignUpInput {
  fullName: string;
  email: string;
  password: string;
  confirmPassword: string;
  inviteCode: string;
}

export interface SignInInput {
  email: string;
  password: string;
}

export function validateFullName(value: string): string | undefined {
  const name = value.trim();
  if (name === '') {
    return 'Enter your full name.';
  }
  if (name.length < 2) {
    return 'Enter at least 2 characters.';
  }
  if (name.length > 120) {
    return 'Keep your name under 120 characters.';
  }
  return undefined;
}

export function validateEmail(value: string): string | undefined {
  const email = value.trim();
  if (email === '') {
    return 'Enter your email address.';
  }
  if (email.length > 254) {
    return 'That email address is too long.';
  }
  if (!EMAIL_PATTERN.test(email)) {
    return 'Enter a valid email address, for example you@company.com.';
  }
  return undefined;
}

/**
 * Every reason a password is unacceptable, so the UI can show a checklist
 * rather than one message at a time.
 */
export function passwordProblems(value: string): string[] {
  const problems: string[] = [];
  if (value === '') {
    return ['Enter a password.'];
  }
  if (value.length < PASSWORD_MIN_LENGTH) {
    problems.push(`Use at least ${PASSWORD_MIN_LENGTH} characters.`);
  }
  if (value.length > PASSWORD_MAX_LENGTH) {
    problems.push(`Keep your password under ${PASSWORD_MAX_LENGTH} characters.`);
  }
  if (!HAS_LETTER.test(value)) {
    problems.push('Include at least one letter.');
  }
  if (!HAS_DIGIT.test(value)) {
    problems.push('Include at least one number.');
  }
  return problems;
}

export function validatePassword(value: string): string | undefined {
  const problems = passwordProblems(value);
  return problems.length === 0 ? undefined : problems.join(' ');
}

export function validateConfirmPassword(password: string, confirmation: string): string | undefined {
  if (confirmation === '') {
    return 'Re-enter your password to confirm it.';
  }
  if (password !== confirmation) {
    return 'Those passwords do not match.';
  }
  return undefined;
}

/**
 * @param required whether the server demands a code (from the registration
 *                 policy endpoint). When the policy is unknown the field is
 *                 validated leniently, because refusing a sign-up locally for a
 *                 field the server would ignore would be worse than letting the
 *                 server decide.
 */
export function validateInviteCode(value: string, required: boolean): string | undefined {
  const code = value.trim();
  if (code === '') {
    return required ? 'A registration invite code is required to create an account.' : undefined;
  }
  if (code.length > 200) {
    return 'That invite code is too long.';
  }
  return undefined;
}

export function validateSignUp(input: SignUpInput, inviteCodeRequired = false): FieldErrors<SignUpField> {
  const errors: FieldErrors<SignUpField> = {};

  const fullName = validateFullName(input.fullName);
  if (fullName) errors.fullName = fullName;

  const email = validateEmail(input.email);
  if (email) errors.email = email;

  const password = validatePassword(input.password);
  if (password) errors.password = password;

  // Only check the confirmation once the password itself is acceptable —
  // otherwise a too-short password also reports a misleading mismatch.
  if (!password) {
    const confirmPassword = validateConfirmPassword(input.password, input.confirmPassword);
    if (confirmPassword) errors.confirmPassword = confirmPassword;
  }

  const inviteCode = validateInviteCode(input.inviteCode, inviteCodeRequired);
  if (inviteCode) errors.inviteCode = inviteCode;

  return errors;
}

export function validateSignIn(input: SignInInput): FieldErrors<SignInField> {
  const errors: FieldErrors<SignInField> = {};

  const email = validateEmail(input.email);
  if (email) errors.email = email;

  if (input.password === '') {
    errors.password = 'Enter your password.';
  }

  return errors;
}

export function validateForgotPassword(
  input: { email: string },
): FieldErrors<ForgotPasswordField> {
  const errors: FieldErrors<ForgotPasswordField> = {};

  const email = validateEmail(input.email);
  if (email) errors.email = email;

  return errors;
}

/** True when a validation result contains no messages. */
export function isValid(errors: Record<string, string | undefined>): boolean {
  return Object.values(errors).every((message) => message === undefined);
}
