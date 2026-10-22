import { useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { toApiError } from '../../api/client';
import { kioskApi } from '../../api/device';
import type { ApiErrorBody } from '../../api/types';
import { AgencyBrand } from '../../components/AgencyBrand';
import { ErrorAlert } from '../../components/ErrorAlert';
import { DeviceShell } from './DeviceShell';

const LABELS = ['Very poor', 'Poor', 'Fair', 'Good', 'Excellent'];

function FeedbackForm({ ticketId }: { ticketId: string }) {
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!rating) return;
    setBusy(true);
    setError(null);
    try {
      await kioskApi.feedback({ ticketId, rating, comment: comment.trim() || undefined });
      setDone(true);
    } catch (err) {
      setError(toApiError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="kiosk d-flex flex-column">
      <header className="kiosk-header px-4 py-3"><AgencyBrand /></header>
      <div className="container py-5" style={{ maxWidth: 720 }} aria-live="polite">
        {done ? (
          <div className="text-center">
            <h1 className="display-5">Thank you!</h1>
            <p className="fs-3">Your feedback helps us improve our service.</p>
          </div>
        ) : (
          <form onSubmit={submit}>
            <h1 className="display-6 text-center mb-4">How was our service today?</h1>
            <ErrorAlert error={error} />
            <fieldset className="mb-4">
              <legend className="visually-hidden">Rating from 1 to 5</legend>
              <div className="d-flex justify-content-center gap-2 flex-wrap">
                {LABELS.map((label, i) => {
                  const value = i + 1;
                  return (
                    <div key={value}>
                      <input type="radio" className="btn-check" name="rating" id={`rating-${value}`} value={value}
                        checked={rating === value} onChange={() => setRating(value)} />
                      <label className="btn btn-outline-warning btn-lg px-4 py-3" htmlFor={`rating-${value}`}>
                        <span aria-hidden="true" className="d-block fs-1">{'★'.repeat(value)}</span>
                        {label}
                      </label>
                    </div>
                  );
                })}
              </div>
            </fieldset>
            <label htmlFor="comment" className="form-label fs-5">Comments (optional, please do not include personal information)</label>
            <textarea id="comment" className="form-control form-control-lg mb-4" rows={3} maxLength={500}
              value={comment} onChange={(e) => setComment(e.target.value)} />
            <button type="submit" className="btn btn-primary btn-lg w-100 kiosk-btn" disabled={!rating || busy}>
              {busy ? 'Sending…' : 'Submit feedback'}
            </button>
          </form>
        )}
      </div>
    </main>
  );
}

/** Client feedback for a ticket, posted with the device key (/kiosk/feedback). */
export function FeedbackPage() {
  const { ticketId = '' } = useParams();
  return (
    <DeviceShell kind="Kiosk" verify={() => kioskApi.config()}>
      <FeedbackForm ticketId={ticketId} />
    </DeviceShell>
  );
}
