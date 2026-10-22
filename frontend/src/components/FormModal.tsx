import { useState, type FormEvent, type ReactNode } from 'react';
import { toApiError } from '../api/client';
import type { ApiErrorBody } from '../api/types';
import { ErrorAlert, fieldError } from './ErrorAlert';
import { Modal } from './Modal';

interface Props {
  title: string;
  submitLabel?: string;
  size?: 'sm' | 'lg' | 'xl';
  /** Client-side validation; return a message to block submit. */
  validate?: () => string | null;
  onSubmit: () => Promise<unknown>;
  onClose: () => void;
  /** Render prop receiving a lookup for server-side field errors. */
  children: (fe: (field: string) => string | undefined) => ReactNode;
}

/** Modal form with shared submit/busy/error handling; closes on success. */
export function FormModal({ title, submitLabel = 'Save', size, validate, onSubmit, onClose, children }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const msg = validate?.();
    if (msg) {
      setError({ status: 0, code: 'VALIDATION_FAILED', message: msg });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSubmit();
      onClose();
    } catch (err) {
      setError(toApiError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={title} onClose={onClose} size={size}>
      <form onSubmit={submit} noValidate>
        <ErrorAlert error={error} />
        {children((f) => fieldError(error, f))}
        <div className="d-flex justify-content-end gap-2 mt-2">
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Saving…' : submitLabel}
          </button>
        </div>
      </form>
    </Modal>
  );
}
