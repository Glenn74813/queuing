import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toApiError } from '../../api/client';
import { kioskApi } from '../../api/device';
import type { Channel, IssueTicketRequest, IssuedTicket, KioskConfig, KioskService } from '../../api/types';
import { AGENCY_NAME, AgencyBrand } from '../../components/AgencyBrand';
import { QrCode } from '../../components/QrCode';
import { useClock } from '../../hooks/useClock';
import { useIdleTimer } from '../../hooks/useIdleTimer';
import { formatDateTime } from '../../utils/format';
import { issueWithRetry, newIssueRequest } from '../../utils/kioskIssue';
import { ticketLink } from '../../utils/ticketLink';
import { ContentRotator, Ticker } from './ContentRotator';
import { DeviceShell, isContentLive } from './DeviceShell';

/** Return to the home screen after this much inactivity on any other step. */
export const KIOSK_IDLE_MS = 20_000;
const CONFIG_REFRESH_MS = 30_000;

type Step =
  | { name: 'home' }
  | { name: 'priority'; service: KioskService }
  | { name: 'channel'; service: KioskService; priorityId?: string; priorityName?: string }
  | { name: 'issuing' }
  | { name: 'result'; ticket: IssuedTicket }
  | { name: 'rate' }
  | { name: 'error'; message: string; retriable: boolean };

/** Maps an issuance failure to a message suitable for the public. */
function friendlyIssueError(err: unknown): { message: string; retriable: boolean } {
  const e = toApiError(err);
  if (e.status === 0) return { message: 'We cannot connect to the queue system right now. Please try again in a moment.', retriable: true };
  if (e.status >= 500) return { message: 'The queue system is busy. Please try again.', retriable: true };
  if (e.status === 401 || e.status === 403) return { message: 'This kiosk is not authorized. Please ask a staff member for help.', retriable: false };
  if (e.status === 429) return { message: 'Too many requests. Please wait a moment and try again.', retriable: true };
  // 409/422 etc: closed service, outside hours, daily limit reached…
  return { message: 'Sorry, this service is not available right now. Please choose another service or ask a staff member.', retriable: false };
}

/** On-screen keypad for typing a ticket number: the site's service prefixes, digits and backspace. */
function RateLookup({ prefixes, onCancel }: { prefixes: string[]; onCancel: () => void }) {
  const navigate = useNavigate();
  const [number, setNumber] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const letters = Array.from(new Set(prefixes.join('').split(''))).sort();
  const press = (k: string) => {
    setMessage(null);
    setNumber((n) => (n + k).slice(0, 10));
  };
  const find = async () => {
    setBusy(true);
    try {
      const t = await kioskApi.feedbackTicket(number);
      navigate(`/feedback/${t.ticketId}`);
    } catch (err) {
      const e = toApiError(err);
      setMessage(e.status === 404
        ? 'We could not find a finished transaction with that number today, or it has already been rated.'
        : 'We cannot connect to the queue system right now. Please try again.');
    } finally {
      setBusy(false);
    }
  };
  const key = (k: string) => (
    <button key={k} type="button" className="btn btn-outline-primary btn-lg kiosk-key" onClick={() => press(k)}>{k}</button>
  );
  return (
    <section className="text-center" aria-labelledby="rate-heading">
      <h1 id="rate-heading" className="display-6 mb-2">Rate our service</h1>
      <p className="fs-4 mb-3">Enter the number on your ticket</p>
      <output className="kiosk-ticket-number d-block mb-3" aria-live="polite">{number || '—'}</output>
      {message && <div className="alert alert-warning fs-5" role="alert">{message}</div>}
      <div className="d-flex flex-wrap justify-content-center gap-2 mb-2">{letters.map(key)}</div>
      <div className="d-flex flex-wrap justify-content-center gap-2 mb-3" style={{ maxWidth: 520, margin: '0 auto' }}>
        {['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'].map(key)}
        <button type="button" className="btn btn-outline-secondary btn-lg kiosk-key" aria-label="Delete last character"
          onClick={() => setNumber((n) => n.slice(0, -1))}>⌫</button>
      </div>
      <div className="d-flex justify-content-center gap-3">
        <button type="button" className="btn btn-outline-secondary btn-lg" onClick={onCancel}>Cancel</button>
        <button type="button" className="btn btn-primary btn-lg px-5" disabled={number.length < 2 || busy} onClick={() => void find()}>
          {busy ? 'Searching…' : 'Continue'}
        </button>
      </div>
    </section>
  );
}

/** Print-only 80 mm ticket layout (see @media print in styles.css). */
function PrintTicket({ ticket, link }: { ticket: IssuedTicket; link: string | null }) {
  return (
    <div className="print-ticket" aria-hidden="true">
      <div className="pt-site">{AGENCY_NAME}</div>
      <div className="pt-site">{ticket.siteName}</div>
      <hr />
      <div className="pt-line">Your number</div>
      <div className="pt-number">{ticket.displayNumber}</div>
      <div className="pt-line">{ticket.serviceName}</div>
      {ticket.priorityName && <div className="pt-line"><strong>Priority: {ticket.priorityName}</strong></div>}
      <hr />
      <div className="pt-line">Issued: {formatDateTime(ticket.issuedAt)}</div>
      <div className="pt-line">Waiting ahead of you: {ticket.waitingAhead}</div>
      <div className="pt-line">Estimated wait: ~{ticket.estimatedWaitMinutes} min</div>
      <hr />
      <div className="pt-line">Please watch the display for your number.</div>
      {link && (
        <>
          <QrCode value={link} label="" size={110} className="pt-qr" />
          <div className="pt-line">Scan to follow your queue and rate our service</div>
        </>
      )}
    </div>
  );
}

function KioskApp() {
  const [config, setConfig] = useState<KioskConfig | null>(null);
  const [offline, setOffline] = useState(false);
  const [step, setStep] = useState<Step>({ name: 'home' });
  // The in-flight request is kept so a retry re-sends the SAME requestId (idempotent issuance).
  const pending = useRef<IssueTicketRequest | null>(null);
  const now = useClock(30_000);

  const loadConfig = useCallback(async () => {
    try {
      setConfig(await kioskApi.config());
      setOffline(false);
    } catch {
      setOffline(true);
    }
  }, []);

  useEffect(() => {
    void loadConfig();
    const t = window.setInterval(() => void loadConfig(), CONFIG_REFRESH_MS);
    return () => window.clearInterval(t);
  }, [loadConfig]);

  const goHome = useCallback(() => {
    pending.current = null;
    setStep({ name: 'home' });
    void loadConfig();
  }, [loadConfig]);
  useIdleTimer(KIOSK_IDLE_MS, goHome, step.name !== 'home' && step.name !== 'issuing');

  const live = useMemo(() => (config?.content ?? []).filter((c) => isContentLive(c, now)), [config, now]);
  const announcements = live.filter((c) => c.type === 'ANNOUNCEMENT');
  const images = live.filter((c) => c.type === 'IMAGE');
  const services = (config?.services ?? []).slice();
  const priorities = config?.priorityCategories ?? [];

  const send = async (req: IssueTicketRequest) => {
    pending.current = req;
    setStep({ name: 'issuing' });
    try {
      const ticket = await issueWithRetry(kioskApi.issueTicket, req);
      pending.current = null;
      setStep({ name: 'result', ticket });
    } catch (err) {
      const f = friendlyIssueError(err);
      if (!f.retriable) pending.current = null;
      setStep({ name: 'error', ...f });
    }
  };

  const issue = (service: KioskService, channel: Channel, priorityId?: string) =>
    void send(newIssueRequest({ siteServiceId: service.siteServiceId, priorityCategoryId: priorityId, channel }));

  // Print once the result (and the hidden print layout) is rendered.
  useEffect(() => {
    if (step.name === 'result' && step.ticket.channel === 'PRINTED') {
      const t = window.setTimeout(() => window.print(), 300);
      return () => window.clearTimeout(t);
    }
  }, [step]);

  const link = step.name === 'result' && step.ticket.publicToken
    ? ticketLink(step.ticket.publicToken, config?.ticketLinkBaseUrl) : null;

  const chooseService = (service: KioskService) =>
    setStep(priorities.length > 0 ? { name: 'priority', service } : { name: 'channel', service });

  return (
    <div className="kiosk d-flex flex-column">
      <header className="kiosk-header d-flex justify-content-between align-items-center px-4 py-3">
        <AgencyBrand size="lg" />
        <div className="text-end">
          <div className="fs-4 fw-bold">{config?.site.name ?? 'Queueing System'}</div>
          <div>{now.toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' })}</div>
        </div>
      </header>

      {offline && (
        <div className="alert alert-warning rounded-0 mb-0 text-center fs-5" role="status">
          Connection problem — some information may be out of date.
        </div>
      )}

      <main className="flex-grow-1 container py-4" aria-live="polite">
        {step.name === 'home' && (
          <>
            {images.length > 0 && (
              <div className="mb-4 rounded overflow-hidden bg-dark" style={{ height: '28vh' }}>
                <ContentRotator items={images} />
              </div>
            )}
            <h1 className="display-6 text-center mb-4">Please select a service</h1>
            {!config && !offline && <p className="text-center fs-4">Loading services…</p>}
            <div className="row g-3">
              {services.map((s) => (
                <div key={s.siteServiceId} className="col-md-6">
                  <button type="button" className={`btn ${s.open ? 'btn-primary' : 'btn-secondary'} kiosk-btn w-100`}
                    disabled={!s.open} onClick={() => chooseService(s)}>
                    {s.name}
                    <small>
                      {s.open ? `${s.waitingCount} waiting · about ${s.estimatedWaitMinutes} min` : 'Not available at this time'}
                    </small>
                  </button>
                </div>
              ))}
            </div>
            {config && (
              <div className="text-center mt-4">
                <button type="button" className="btn btn-outline-warning btn-lg px-5" onClick={() => setStep({ name: 'rate' })}>
                  <span aria-hidden="true">★ </span>Rate our service
                </button>
              </div>
            )}
          </>
        )}

        {step.name === 'rate' && <RateLookup prefixes={services.map((s) => s.prefix)} onCancel={goHome} />}

        {step.name === 'priority' && (
          <section className="text-center">
            <h1 className="display-6 mb-2">Do you qualify for priority lane?</h1>
            <p className="fs-4 mb-4">
              Are you a {priorities.map((p) => p.name.toLowerCase()).join(', ')}? Please choose below.
            </p>
            <div className="row g-3 justify-content-center">
              {priorities.map((p) => (
                <div key={p.id} className="col-md-6">
                  <button type="button" className="btn btn-warning kiosk-btn w-100"
                    onClick={() => setStep({ name: 'channel', service: step.service, priorityId: p.id, priorityName: p.name })}>
                    {p.name}
                    {p.description && <small>{p.description}</small>}
                  </button>
                </div>
              ))}
              <div className="col-md-6">
                <button type="button" className="btn btn-outline-primary kiosk-btn w-100"
                  onClick={() => setStep({ name: 'channel', service: step.service })}>
                  Regular
                  <small>None of the above</small>
                </button>
              </div>
            </div>
            <button type="button" className="btn btn-link fs-4 mt-4" onClick={goHome}>Back</button>
          </section>
        )}

        {step.name === 'channel' && (
          <section className="text-center">
            <h1 className="display-6 mb-2">{step.service.name}</h1>
            {step.priorityName && <p className="fs-4">Priority: {step.priorityName}</p>}
            <p className="fs-4 mb-4">How would you like your ticket?</p>
            <div className="row g-3 justify-content-center">
              <div className="col-md-5">
                <button type="button" className="btn btn-primary kiosk-btn w-100" onClick={() => issue(step.service, 'PRINTED', step.priorityId)}>
                  Print ticket
                </button>
              </div>
              <div className="col-md-5">
                <button type="button" className="btn btn-outline-primary kiosk-btn w-100" onClick={() => issue(step.service, 'ELECTRONIC', step.priorityId)}>
                  Show on screen only
                  <small>Take a photo of your number</small>
                </button>
              </div>
            </div>
            <button type="button" className="btn btn-link fs-4 mt-4" onClick={goHome}>Cancel</button>
          </section>
        )}

        {step.name === 'issuing' && (
          <div className="text-center py-5" role="status">
            <div className="spinner-border text-primary" style={{ width: '4rem', height: '4rem' }} aria-hidden="true" />
            <p className="fs-3 mt-3">Getting your number…</p>
          </div>
        )}

        {step.name === 'result' && (
          <section className="text-center" aria-labelledby="ticket-heading">
            <h1 id="ticket-heading" className="fs-3">Your queue number is</h1>
            <div className="kiosk-ticket-number my-3">{step.ticket.displayNumber}</div>
            <p className="fs-3 mb-1">{step.ticket.serviceName}</p>
            {step.ticket.priorityName && <p className="fs-4 mb-1">Priority: {step.ticket.priorityName}</p>}
            <p className="fs-4">
              {step.ticket.waitingAhead} ahead of you · about {step.ticket.estimatedWaitMinutes} min
            </p>
            <p className="fs-5 text-body-secondary">
              {step.ticket.channel === 'PRINTED' ? 'Please take your printed ticket.' : 'Please remember or photograph this number.'} Watch the display for your number.
            </p>
            {link && (
              <div className="d-inline-flex align-items-center gap-3 border rounded p-3 bg-white text-start">
                <QrCode value={link} label={`QR code linking to ticket ${step.ticket.displayNumber} on your phone`} size={150} />
                <div className="fs-5" style={{ maxWidth: 260 }}>
                  <strong>Scan with your phone</strong> to follow your place in line and rate our service afterwards.
                </div>
              </div>
            )}
            <div className="d-flex justify-content-center gap-3 mt-4">
              {step.ticket.channel === 'PRINTED' && (
                <button type="button" className="btn btn-outline-primary btn-lg" onClick={() => window.print()}>Print again</button>
              )}
              <button type="button" className="btn btn-primary btn-lg px-5" onClick={goHome}>Done</button>
            </div>
            <PrintTicket ticket={step.ticket} link={link} />
          </section>
        )}

        {step.name === 'error' && (
          <section className="text-center py-4" role="alert">
            <h1 className="display-6">Sorry!</h1>
            <p className="fs-3">{step.message}</p>
            <div className="d-flex justify-content-center gap-3 mt-4">
              {step.retriable && pending.current && (
                <button type="button" className="btn btn-primary btn-lg px-5"
                  onClick={() => pending.current && void send(pending.current)}>
                  Try again
                </button>
              )}
              <button type="button" className="btn btn-outline-secondary btn-lg" onClick={goHome}>Start over</button>
            </div>
          </section>
        )}
      </main>

      <Ticker items={announcements} />
    </div>
  );
}

export function KioskPage() {
  return (
    <DeviceShell kind="Kiosk" verify={() => kioskApi.config()}>
      <KioskApp />
    </DeviceShell>
  );
}
