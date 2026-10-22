import { useId, useState, type FormEvent, type ReactNode } from 'react';
import { toApiError } from '../api/client';
import type { ApiErrorBody } from '../api/types';
import { ErrorAlert, fieldError } from './ErrorAlert';
import { Modal } from './Modal';

interface Props {
  title: string;
  description?: ReactNode;
  confirmLabel?: string;
  variant?: 'primary' | 'danger' | 'warning';
  /** Optional extra controls rendered above the reason box (e.g. a target-service picker). */
  extra?: ReactNode;
  /** Disables submit while extra input is incomplete. */
  extraValid?: boolean;
  onSubmit: (reason: string) => unknown | Promise<unknown>;
  onClose: () => void;
}

/** Modal that requires a non-blank reason before performing an action (hold, remove, void, reject…). */
export function ReasonDialog({
  title,
  description,
  confirmLabel = 'Submit',
  variant = 'primary',
  extra,
  extraValid = true,
  onSubmit,
  onClose,
}: Props) {
  const id = useId();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);
  const trimmed = reason.trim();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!trimmed || !extraValid) return;
    setBusy(true);
    setError(null);
    try {
      await onSubmit(trimmed);
      onClose();
    } catch (err) {
      setError(toApiError(err));
    } finally {
      setBusy(false);
    }
  };

  const reasonErr = fieldError(error, 'reason');
  return (
    <Modal title={title} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ErrorAlert error={error} />
        {description && <p>{description}</p>}
        {extra}
        <div className="mb-3">
          <label htmlFor={`${id}-reason`} className="form-label">
            Reason <span aria-hidden="true">*</span>
          </label>
          <textarea
            id={`${id}-reason`}
            className={`form-control ${reasonErr ? 'is-invalid' : ''}`}
            rows={3}
            required
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {reasonErr && <div className="invalid-feedback">{reasonErr}</div>}
        </div>
        <div className="d-flex justify-content-end gap-2">
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className={`btn btn-${variant}`} disabled={busy || !trimmed || !extraValid}>
            {busy ? 'Working…' : confirmLabel}
          </button>
        </div>
      </form>
    </Modal>
  );
}
