import React, { useId, useState } from 'react';

/**
 * Labelled text input with inline validation and an optional show/hide control
 * for passwords.
 *
 * Accessibility details that matter for a form people actually have to complete:
 * the label is associated with the input, the error is referenced via
 * `aria-describedby` and marked `role="alert"`, and `aria-invalid` reflects the
 * invalid state so assistive technology announces it.
 */

interface FormFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: 'text' | 'email' | 'password';
  placeholder?: string;
  autoComplete?: string;
  autoFocus?: boolean;
  disabled?: boolean;
  error?: string;
  /** Hint shown below the field when there is no error. */
  hint?: string;
  /** Renders a show/hide toggle and starts masked. Only sensible for passwords. */
  revealable?: boolean;
}

export const FormField: React.FC<FormFieldProps> = ({
  label,
  value,
  onChange,
  type = 'text',
  placeholder,
  autoComplete,
  autoFocus,
  disabled,
  error,
  hint,
  revealable = false,
}) => {
  const inputId = useId();
  const errorId = `${inputId}-error`;
  const hintId = `${inputId}-hint`;
  const [revealed, setRevealed] = useState(false);

  const resolvedType = revealable ? (revealed ? 'text' : 'password') : type;
  const describedBy = [error ? errorId : null, hint && !error ? hintId : null]
    .filter(Boolean)
    .join(' ');

  return (
    <div>
      <label
        htmlFor={inputId}
        className="mb-1.5 block text-xs font-semibold text-ink-soft"
      >
        {label}
      </label>

      <div className="relative">
        <input
          id={inputId}
          type={resolvedType}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          autoComplete={autoComplete}
          autoFocus={autoFocus}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy === '' ? undefined : describedBy}
          className={`w-full rounded-lg border bg-surface px-3.5 py-2.5 text-ink placeholder-ink-faint transition-colors focus:outline-none focus:ring-2 disabled:cursor-not-allowed disabled:opacity-60 ${
            revealable ? 'pr-16' : ''
          } ${
            error
              ? 'border-red-400 focus:ring-red-500/30'
              : 'border-line focus:border-forest-600 focus:ring-forest-600/20'
          }`}
        />

        {revealable && (
          <button
            type="button"
            onClick={() => setRevealed((current) => !current)}
            disabled={disabled}
            aria-label={revealed ? 'Hide password' : 'Show password'}
            className="absolute inset-y-0 right-0 px-3 text-xs font-semibold uppercase tracking-wider text-ink-muted transition-colors hover:text-forest-700 disabled:opacity-50"
          >
            {revealed ? 'Hide' : 'Show'}
          </button>
        )}
      </div>

      {error && (
        <p id={errorId} role="alert" className="mt-1.5 text-xs text-red-700">
          {error}
        </p>
      )}
      {hint && !error && (
        <p id={hintId} className="mt-1.5 text-xs text-ink-muted">
          {hint}
        </p>
      )}
    </div>
  );
};
