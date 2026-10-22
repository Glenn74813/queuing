import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { kioskApi } from '../../api/device';
import { AgencyBrand } from '../../components/AgencyBrand';
import { RatingForm } from '../../components/RatingForm';
import { useIdleTimer } from '../../hooks/useIdleTimer';
import { KIOSK_IDLE_MS } from './KioskPage';
import { DeviceShell } from './DeviceShell';

/** After "Thank you", the kiosk returns to its home screen. */
const THANKS_MS = 6000;

function FeedbackForm({ ticketId }: { ticketId: string }) {
  const navigate = useNavigate();
  const [done, setDone] = useState(false);
  const home = () => navigate('/kiosk', { replace: true });
  useIdleTimer(KIOSK_IDLE_MS * 3, home, !done);

  useEffect(() => {
    if (!done) return;
    const t = window.setTimeout(() => navigate('/kiosk', { replace: true }), THANKS_MS);
    return () => window.clearTimeout(t);
  }, [done, navigate]);

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
          <>
            <h1 className="display-6 text-center mb-4">How was our service today?</h1>
            <RatingForm onSubmit={async (rating, comment) => {
              await kioskApi.feedback({ ticketId, rating, comment });
              setDone(true);
            }} />
            <div className="text-center mt-3">
              <button type="button" className="btn btn-link fs-4" onClick={home}>Cancel</button>
            </div>
          </>
        )}
      </div>
    </main>
  );
}

/** Client feedback for a ticket, posted with the device key (/kiosk/feedback). Reached from the kiosk's "Rate our service". */
export function FeedbackPage() {
  const { ticketId = '' } = useParams();
  return (
    <DeviceShell kind="Kiosk" verify={() => kioskApi.config()}>
      <FeedbackForm ticketId={ticketId} />
    </DeviceShell>
  );
}
