import { useState, type ReactNode } from 'react';
import { toApiError } from '../api/client';
import type { ApiErrorBody } from '../api/types';
import { ErrorAlert } from './ErrorAlert';
import { Modal } from './Modal';

interface Props {
  title: string;
  message: ReactNode;
  confirmLabel?: string;
  variant?: 'primary' | 'danger' | 'warning' | 'success';
  /** May return a promise; errors are rendered inside the dialog and keep it open. */
  onConfirm: () => unknown | Promise<unknown>;
  onClose: () => void;
}

export function ConfirmDialog({ title, message, confirmLabel = 'Confirm', variant = 'primary', onConfirm, onClose }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);

  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
      onClose();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="button" className={`btn btn-${variant}`} onClick={confirm} disabled={busy}>
            {busy ? 'Working…' : confirmLabel}
          </button>
        </>
      }
    >
      <ErrorAlert error={error} />
      <div>{message}</div>
    </Modal>
  );
}
