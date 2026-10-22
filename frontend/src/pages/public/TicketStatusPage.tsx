import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { toApiError } from '../../api/client';
import { publicTicketApi } from '../../api/publicTicket';
import type { PublicTicketStatus } from '../../api/types';
import { AgencyBrand } from '../../components/AgencyBrand';
import { RatingForm } from '../../components/RatingForm';

const POLL_MS = 15_000;

const MESSAGES: Partial<Record<PublicTicketStatus['status'], string>> = {
  ON_HOLD: 'Your transaction is on hold. Please stay near the counter.',
  COMPLETED: 'Your transaction is complete. Thank you for visiting!',
  REMOVED: 'This ticket was removed from the queue. Please ask a staff member if you still need help.',
  VOIDED: 'This ticket is no longer valid. Please ask a staff member for help.',
  EXPIRED: 'This ticket has expired.',
};

/** Client's phone view of a ticket, opened from the kiosk QR code. Public: the link token is the only key. */
export function TicketStatusPage() {
  const { token = '' } = useParams();
  const [ticket, setTicket] = useState<PublicTicketStatus | null>(null);
  const [problem, setProblem] = useState<'missing' | 'offline' | null>(null);
  const [thanks, setThanks] = useState(false);

  const load = useCallback(async () => {
    try {
      setTicket(await publicTicketApi.status(token));
      setProblem(null);
    } catch (err) {
      setProblem(toApiError(err).status === 404 ? 'missing' : 'offline');
    }
  }, [token]);

  const finished = ticket != null && !['WAITING', 'CALLED', 'ON_HOLD'].includes(ticket.status);
  useEffect(() => {
    void load();
    if (finished) return;
    const t = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(t);
  }, [load, finished]);

  useEffect(() => {
    document.title = ticket ? `${ticket.displayNumber} — ${ticket.status === 'CALLED' ? 'Now calling' : 'Queue'}` : 'Your ticket';
  }, [ticket]);

  return (
    <main className="min-vh-100 bg-body-tertiary">
      <header className="kiosk-header px-3 py-2"><AgencyBrand /></header>
      <div className="container py-4" style={{ maxWidth: 480 }}>
        {problem === 'missing' && (
          <div className="alert alert-warning" role="alert">
            This ticket link is not valid or has expired. Please check the number on your printed ticket.
          </div>
        )}
        {problem === 'offline' && ticket && (
          <div className="alert alert-secondary small" role="status">Cannot refresh right now. Showing the last update.</div>
        )}
        {problem === 'offline' && !ticket && (
          <div className="alert alert-warning" role="alert">We cannot reach the queue system right now. Please try again shortly.</div>
        )}
        {!ticket && !problem && <p className="text-center text-body-secondary">Loading your ticket…</p>}

        {ticket && (
          <section className="card shadow-sm text-center" aria-live="polite">
            <div className="card-body">
              <div className="text-body-secondary small">{ticket.siteName}</div>
              <div className="small">Your number</div>
              <div className="display-3 fw-bold">{ticket.displayNumber}</div>
              <div className="mb-3">{ticket.serviceName}</div>

              {ticket.status === 'WAITING' && (
                <>
                  <p className="fs-5 mb-1">
                    {ticket.waitingAhead === 0 ? 'You are next in line.' : `${ticket.waitingAhead} ahead of you`}
                  </p>
                  {ticket.waitingAhead > 0 && <p className="text-body-secondary">About {ticket.estimatedWaitMinutes} min</p>}
                  <p className="small text-body-secondary mb-0">This page updates by itself. Please stay inside the office.</p>
                </>
              )}
              {ticket.status === 'CALLED' && (
                <div className="alert alert-success fs-4 mb-0" role="alert">
                  Now calling! Please proceed to <strong>{ticket.counterName}</strong>.
                </div>
              )}
              {MESSAGES[ticket.status] && <p className="fs-5 mb-0">{MESSAGES[ticket.status]}</p>}
            </div>
          </section>
        )}

        {ticket?.feedbackOpen && !thanks && (
          <section className="card shadow-sm mt-3" aria-labelledby="fb-heading">
            <div className="card-body">
              <h2 id="fb-heading" className="h5 text-center mb-3">How was our service today?</h2>
              <RatingForm size="phone" onSubmit={async (rating, comment) => {
                await publicTicketApi.feedback(token, { rating, comment });
                setThanks(true);
              }} />
            </div>
          </section>
        )}
        {(thanks || ticket?.feedbackGiven) && (
          <p className="text-center mt-3" role="status">Thank you for your feedback!</p>
        )}
      </div>
    </main>
  );
}
