import type { ApiErrorBody } from '../api/types';

interface Props {
  error: ApiErrorBody | null | undefined;
  onDismiss?: () => void;
  className?: string;
}

/** Friendly titles for the contract's error codes. */
const CODE_TITLES: Record<string, string> = {
  VALIDATION_FAILED: 'Please correct the highlighted fields',
  NOT_FOUND: 'Not found',
  FORBIDDEN: 'Not allowed',
  UNAUTHORIZED: 'Please sign in again',
  INVALID_TRANSITION: 'Action not allowed in the current state',
  CONFLICT: 'Conflict',
  ACCOUNT_LOCKED: 'Account locked',
  RATE_LIMITED: 'Too many attempts',
  PASSWORD_CHANGE_REQUIRED: 'Password change required',
  INTERNAL_ERROR: 'Server error',
  NETWORK_ERROR: 'Connection problem',
};

/** Renders the contract's error body: message, code and per-field validation errors. */
export function ErrorAlert({ error, onDismiss, className = '' }: Props) {
  if (!error) return null;
  const title = (error.code && CODE_TITLES[error.code]) || 'Something went wrong';
  return (
    <div className={`alert alert-danger ${onDismiss ? 'alert-dismissible' : ''} ${className}`} role="alert">
      <strong className="d-block">{title}</strong>
      <span>{error.message}</span>
      {error.fieldErrors && error.fieldErrors.length > 0 && (
        <ul className="mb-0 mt-2" aria-label="Field errors">
          {error.fieldErrors.map((fe, i) => (
            <li key={`${fe.field}-${i}`}>
              <code>{fe.field}</code>: {fe.message}
            </li>
          ))}
        </ul>
      )}
      {onDismiss && <button type="button" className="btn-close" aria-label="Dismiss error" onClick={onDismiss} />}
    </div>
  );
}

/** Looks up the message for one field in an error body (for inline `invalid-feedback`). */
export function fieldError(error: ApiErrorBody | null | undefined, field: string): string | undefined {
  return error?.fieldErrors?.find((f) => f.field === field)?.message;
}
