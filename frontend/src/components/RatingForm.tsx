import { useId, useState, type FormEvent } from 'react';
import { toApiError } from '../api/client';
import type { ApiErrorBody } from '../api/types';
import { ErrorAlert } from './ErrorAlert';

export const RATING_LABELS = ['Very poor', 'Poor', 'Fair', 'Good', 'Excellent'];

interface Props {
  onSubmit: (rating: number, comment?: string) => Promise<void>;
  /** Large touch targets for the kiosk; compact for phones. */
  size?: 'kiosk' | 'phone';
}

/** One-to-five star rating with an optional comment; used at the kiosk and on the phone ticket page. */
export function RatingForm({ onSubmit, size = 'kiosk' }: Props) {
  const id = useId();
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);
  const kiosk = size === 'kiosk';

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!rating) return;
    setBusy(true);
    setError(null);
    try {
      await onSubmit(rating, comment.trim() || undefined);
    } catch (err) {
      setError(toApiError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit}>
      <ErrorAlert error={error} />
      <fieldset className="mb-4">
        <legend className="visually-hidden">Rating from 1 to 5</legend>
        <div className={`d-flex justify-content-center flex-wrap ${kiosk ? 'gap-2' : 'gap-1'}`}>
          {RATING_LABELS.map((label, i) => {
            const value = i + 1;
            return (
              <div key={value}>
                <input type="radio" className="btn-check" name={`${id}-rating`} id={`${id}-rating-${value}`} value={value}
                  checked={rating === value} onChange={() => setRating(value)} />
                <label className={`btn btn-outline-warning ${kiosk ? 'btn-lg px-4 py-3' : 'px-2 py-2 small'}`}
                  htmlFor={`${id}-rating-${value}`}>
                  <span aria-hidden="true" className={`d-block ${kiosk ? 'fs-1' : 'fs-5'}`}>{'★'.repeat(value)}</span>
                  {label}
                </label>
              </div>
            );
          })}
        </div>
      </fieldset>
      <label htmlFor={`${id}-comment`} className={`form-label ${kiosk ? 'fs-5' : ''}`}>
        Comments (optional, please do not include personal information)
      </label>
      <textarea id={`${id}-comment`} className={`form-control mb-4 ${kiosk ? 'form-control-lg' : ''}`} rows={3}
        maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} />
      <button type="submit" className={`btn btn-primary w-100 ${kiosk ? 'btn-lg kiosk-btn' : ''}`} disabled={!rating || busy}>
        {busy ? 'Sending…' : 'Submit feedback'}
      </button>
    </form>
  );
}
