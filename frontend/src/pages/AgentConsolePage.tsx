import { useCallback, useId, useState } from 'react';
import { agentApi, ticketsApi } from '../api/agent';
import { toApiError } from '../api/client';
import { countersApi } from '../api/counters';
import type { AgentSession, ApiErrorBody, Ticket } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useSite } from '../auth/SiteContext';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { DataTable } from '../components/DataTable';
import { ErrorAlert } from '../components/ErrorAlert';
import { LiveIndicator } from '../components/LiveIndicator';
import { PageHeader } from '../components/PageHeader';
import { ReasonDialog } from '../components/ReasonDialog';
import { NoSiteNotice } from '../components/SiteSelector';
import { Spinner } from '../components/Spinner';
import { StatusBadge } from '../components/StatusBadge';
import { TransferPicker } from '../components/TransferPicker';
import { useAsync } from '../hooks/useAsync';
import { useQueueTopic } from '../hooks/useQueueTopic';
import { staffConnectHeaders } from '../hooks/useSiteQueue';
import { allowedActions } from '../utils/agentActions';
import { formatTime } from '../utils/format';

type DialogState =
  | { kind: 'hold' | 'remove' | 'void' | 'transfer'; ticket: Ticket }
  | { kind: 'break' }
  | { kind: 'endSession' }
  | null;

interface ConsoleState {
  session: AgentSession | null;
  current: Ticket | null;
  held: Ticket[];
}

/** Loads session + current ticket + held tickets in one go so the view is always consistent. */
async function loadConsole(): Promise<ConsoleState> {
  const session = await agentApi.getSession();
  if (!session) return { session: null, current: null, held: [] };
  const [current, held] = await Promise.all([agentApi.currentTicket(), agentApi.heldTickets()]);
  return { session, current, held };
}

/* ---------- Start session ---------- */

function StartSession({ siteId, onStarted }: { siteId: string; onStarted: () => void }) {
  const id = useId();
  const counters = useAsync(() => countersApi.list(siteId), [siteId]);
  const [counterId, setCounterId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);
  const available = (counters.data ?? []).filter((c) => c.active);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      await agentApi.startSession(counterId);
      onStarted();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card" style={{ maxWidth: 520 }}>
      <div className="card-body">
        <h2 className="h5">Start a counter session</h2>
        <ErrorAlert error={counters.error ?? error} />
        {counters.loading ? (
          <Spinner />
        ) : (
          <>
            <label htmlFor={id} className="form-label">
              Counter
            </label>
            <select id={id} className="form-select mb-3" value={counterId} onChange={(e) => setCounterId(e.target.value)}>
              <option value="">Select a counter…</option>
              {available.map((c) => (
                <option key={c.id} value={c.id} disabled={!!c.currentAgent}>
                  {c.name} ({c.code}){c.currentAgent ? ` — in use by ${c.currentAgent.fullName}` : ''}
                </option>
              ))}
            </select>
            <button type="button" className="btn btn-primary" disabled={!counterId || busy} onClick={start}>
              {busy ? 'Starting…' : 'Start session'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}

/* ---------- Page ---------- */

export function AgentConsolePage() {
  const { me } = useAuth();
  const { siteId: selectedSiteId } = useSite();
  const permissions = me?.permissions ?? [];
  const consoleState = useAsync(loadConsole, []);
  const { data, reload } = consoleState;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [dialog, setDialog] = useState<DialogState>(null);
  const [transferTarget, setTransferTarget] = useState('');

  const session = data?.session ?? null;
  const current = data?.current ?? null;
  const held = data?.held ?? [];
  // Prefer the session's site (the counter's site) over the global selector.
  const siteId = session?.siteId ?? selectedSiteId;
  const onBreak = session?.status === 'ON_BREAK';
  const ctx = { hasSession: !!session, onBreak, heldCount: held.length };
  const actions = allowedActions(current?.status ?? null, permissions, ctx);

  const refresh = useCallback(() => void reload(), [reload]);
  const { connected } = useQueueTopic(session ? siteId : null, { refresh, connectHeaders: staffConnectHeaders });

  /** Runs a mutating action with shared busy/error handling, then re-syncs the console. */
  const run = async (fn: () => Promise<unknown>, successMsg?: string) => {
    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      await fn();
      if (successMsg) setInfo(successMsg);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
      await reload();
    }
  };

  const callNext = () =>
    run(async () => {
      const t = await agentApi.callNext();
      if (!t) setInfo('No tickets waiting for this counter.');
    });

  if (consoleState.loading && !data) return <Spinner />;
  if (!session) {
    return (
      <>
        <PageHeader title="Agent Console" />
        <ErrorAlert error={consoleState.error} />
        {selectedSiteId ? <StartSession siteId={selectedSiteId} onStarted={refresh} /> : <NoSiteNotice />}
      </>
    );
  }

  const heldActions = allowedActions('ON_HOLD', permissions, ctx);

  return (
    <>
      <PageHeader
        title="Agent Console"
        subtitle={
          <>
            Counter <strong>{session.counterName ?? session.counterId}</strong> ·{' '}
            <StatusBadge status={onBreak ? 'ON_BREAK' : 'AVAILABLE'} />
            {onBreak && session.breakReason ? ` (${session.breakReason})` : ''}
          </>
        }
        actions={
          <>
            <LiveIndicator connected={connected} />
            {onBreak ? (
              <button type="button" className="btn btn-warning" disabled={busy || !actions.endBreak}
                onClick={() => run(() => agentApi.endBreak(), 'Welcome back.')}>
                End break
              </button>
            ) : (
              <button type="button" className="btn btn-outline-warning" disabled={busy || !actions.startBreak}
                onClick={() => setDialog({ kind: 'break' })}>
                Start break
              </button>
            )}
            <button type="button" className="btn btn-outline-danger" disabled={busy || !actions.endSession}
              title={held.length > 0 ? 'Resolve held tickets before ending the session' : undefined}
              onClick={() => setDialog({ kind: 'endSession' })}>
              End session
            </button>
          </>
        }
      />

      <ErrorAlert error={error ?? consoleState.error} onDismiss={() => setError(null)} />
      <div aria-live="polite">{info && <div className="alert alert-info py-2">{info}</div>}</div>

      <div className="row g-3">
        <section className="col-lg-7" aria-labelledby="current-heading">
          <div className="card h-100">
            <div className="card-body text-center">
              <h2 id="current-heading" className="h6 text-body-secondary text-uppercase">
                Current ticket
              </h2>
              {current ? (
                <>
                  <div className="ticket-display-number text-primary" aria-live="polite">
                    {current.displayNumber}
                  </div>
                  <div className="mt-2">
                    <StatusBadge status={current.status} />{' '}
                    {current.priorityName && <span className="badge text-bg-warning text-dark">{current.priorityName}</span>}
                  </div>
                  <dl className="row small mt-3 mb-0 text-start mx-auto" style={{ maxWidth: 360 }}>
                    <dt className="col-6">Service</dt>
                    <dd className="col-6">{current.serviceName}</dd>
                    <dt className="col-6">Stage</dt>
                    <dd className="col-6">{current.stageNo}</dd>
                    <dt className="col-6">Issued</dt>
                    <dd className="col-6">{formatTime(current.issuedAt)}</dd>
                    <dt className="col-6">Called</dt>
                    <dd className="col-6">{formatTime(current.lastCalledAt)}</dd>
                    <dt className="col-6">Recalls</dt>
                    <dd className="col-6">{current.recallCount}</dd>
                  </dl>
                </>
              ) : (
                <p className="lead my-4">{onBreak ? 'You are on break.' : 'No ticket at your counter.'}</p>
              )}

              <div className="d-grid gap-2 mt-3">
                <button type="button" className="btn btn-primary btn-lg" disabled={busy || !actions.callNext} onClick={callNext}>
                  Call next
                </button>
              </div>
              <div className="d-flex flex-wrap justify-content-center gap-2 mt-2">
                <button type="button" className="btn btn-outline-primary" disabled={busy || !actions.recall}
                  onClick={() => current && run(() => ticketsApi.recall(current.id))}>
                  Recall
                </button>
                <button type="button" className="btn btn-success" disabled={busy || !actions.complete}
                  onClick={() => current && run(() => ticketsApi.complete(current.id), `Ticket ${current.displayNumber} completed.`)}>
                  Complete
                </button>
                <button type="button" className="btn btn-outline-warning" disabled={busy || !actions.hold}
                  onClick={() => current && setDialog({ kind: 'hold', ticket: current })}>
                  Hold
                </button>
                {current?.nextServiceName && (
                  <button type="button" className="btn btn-outline-primary" disabled={busy || !actions.transfer}
                    onClick={() => current && run(() => ticketsApi.nextStage(current.id),
                      `Ticket ${current.displayNumber} sent to ${current.nextServiceName}.`)}>
                    Next stage: {current.nextServiceName}
                  </button>
                )}
                <button type="button" className="btn btn-outline-secondary" disabled={busy || !actions.transfer}
                  onClick={() => current && setDialog({ kind: 'transfer', ticket: current })}>
                  Transfer
                </button>
                <button type="button" className="btn btn-outline-danger" disabled={busy || !actions.remove}
                  onClick={() => current && setDialog({ kind: 'remove', ticket: current })}>
                  Remove
                </button>
                {permissions.includes('TICKET_VOID') && (
                  <button type="button" className="btn btn-danger" disabled={busy || !actions.void}
                    onClick={() => current && setDialog({ kind: 'void', ticket: current })}>
                    Void
                  </button>
                )}
              </div>
            </div>
          </div>
        </section>

        <section className="col-lg-5" aria-labelledby="held-heading">
          <div className="card h-100">
            <div className="card-body">
              <h2 id="held-heading" className="h5">
                Held tickets ({held.length})
              </h2>
              <DataTable
                caption="Tickets on hold at this counter"
                rows={held}
                rowKey={(t) => t.id}
                emptyText="No tickets on hold."
                columns={[
                  { key: 'displayNumber', header: 'Ticket', render: (t) => <strong>{t.displayNumber}</strong> },
                  { key: 'serviceName', header: 'Service' },
                  {
                    key: 'actions',
                    header: <span className="visually-hidden">Actions</span>,
                    className: 'text-end text-nowrap',
                    render: (t) => (
                      <div className="btn-group btn-group-sm">
                        <button type="button" className="btn btn-outline-primary"
                          // A ticket can only be resumed when the counter is free (it returns to CALLED here).
                          disabled={busy || !heldActions.resume || current?.status === 'CALLED'}
                          onClick={() => run(() => ticketsApi.resume(t.id))}>
                          Resume
                        </button>
                        <button type="button" className="btn btn-outline-secondary" disabled={busy || !heldActions.transfer}
                          onClick={() => setDialog({ kind: 'transfer', ticket: t })}>
                          Transfer
                        </button>
                        <button type="button" className="btn btn-outline-danger" disabled={busy || !heldActions.remove}
                          onClick={() => setDialog({ kind: 'remove', ticket: t })}>
                          Remove
                        </button>
                      </div>
                    ),
                  },
                ]}
              />
            </div>
          </div>
        </section>
      </div>

      {dialog?.kind === 'hold' && (
        <ReasonDialog title={`Hold ticket ${dialog.ticket.displayNumber}`} confirmLabel="Hold" variant="warning"
          description="The client steps aside (e.g. to complete documents). You can resume the ticket later."
          onSubmit={(reason) => ticketsApi.hold(dialog.ticket.id, reason).then(refresh)}
          onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'remove' && (
        <ReasonDialog title={`Remove ticket ${dialog.ticket.displayNumber}`} confirmLabel="Remove" variant="danger"
          description="Use for no-shows or clients who left. This ends the ticket."
          onSubmit={(reason) => ticketsApi.remove(dialog.ticket.id, reason).then(refresh)}
          onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'void' && (
        <ReasonDialog title={`Void ticket ${dialog.ticket.displayNumber}`} confirmLabel="Void" variant="danger"
          description="Voiding cancels a ticket issued in error. This is audited."
          onSubmit={(reason) => ticketsApi.void(dialog.ticket.id, reason).then(refresh)}
          onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'transfer' && siteId && (
        <ReasonDialog title={`Transfer ticket ${dialog.ticket.displayNumber}`} confirmLabel="Transfer"
          extra={<TransferPicker siteId={siteId} excludeServiceId={dialog.ticket.siteServiceId}
            value={transferTarget} onChange={setTransferTarget} />}
          extraValid={!!transferTarget}
          onSubmit={(reason) => ticketsApi.transfer(dialog.ticket.id, transferTarget, reason).then(refresh)}
          onClose={() => {
            setDialog(null);
            setTransferTarget('');
          }} />
      )}
      {dialog?.kind === 'break' && (
        <ReasonDialog title="Start break" confirmLabel="Start break" variant="warning"
          onSubmit={(reason) => agentApi.startBreak(reason).then(refresh)} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'endSession' && (
        <ConfirmDialog title="End session" message="End your session at this counter?" confirmLabel="End session"
          variant="danger" onConfirm={() => agentApi.endSession().then(refresh)} onClose={() => setDialog(null)} />
      )}
    </>
  );
}
