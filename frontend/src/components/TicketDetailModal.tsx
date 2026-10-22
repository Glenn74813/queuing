import { useState } from 'react';
import { ticketsApi } from '../api/agent';
import { useAuth } from '../auth/AuthContext';
import { useAsync } from '../hooks/useAsync';
import { allowedActions } from '../utils/agentActions';
import { enumLabel, formatDateTime } from '../utils/format';
import { ErrorAlert } from './ErrorAlert';
import { Modal } from './Modal';
import { ReasonDialog } from './ReasonDialog';
import { Spinner } from './Spinner';
import { StatusBadge } from './StatusBadge';
import { TransferPicker } from './TransferPicker';

interface Props {
  ticketId: string;
  siteId: string;
  onClose: () => void;
  /** Called after a successful supervisor action. */
  onChanged: () => void;
}

/** Ticket detail with event timeline (GET /tickets/{id}) and supervisor interventions. */
export function TicketDetailModal({ ticketId, siteId, onClose, onChanged }: Props) {
  const { me, can } = useAuth();
  const detail = useAsync(() => ticketsApi.get(ticketId), [ticketId]);
  const [action, setAction] = useState<'remove' | 'void' | 'transfer' | null>(null);
  const [target, setTarget] = useState('');
  const t = detail.data;
  // Supervisor interventions require QUEUE_SUPERVISE; void additionally requires TICKET_VOID.
  const supervise = can('QUEUE_SUPERVISE');
  const allowed = allowedActions(t?.status ?? null, me?.permissions ?? [], { hasSession: false });

  const done = () => {
    onChanged();
    void detail.reload();
  };

  return (
    <Modal title={t ? `Ticket ${t.displayNumber}` : 'Ticket'} onClose={onClose} size="lg">
      <ErrorAlert error={detail.error} />
      {!t ? (
        <Spinner />
      ) : (
        <>
          <dl className="row small">
            <dt className="col-sm-3">Status</dt>
            <dd className="col-sm-9"><StatusBadge status={t.status} /></dd>
            <dt className="col-sm-3">Service</dt>
            <dd className="col-sm-9">{t.serviceName} (stage {t.stageNo})</dd>
            <dt className="col-sm-3">Priority</dt>
            <dd className="col-sm-9">{t.priorityName ?? 'Regular'}</dd>
            <dt className="col-sm-3">Channel</dt>
            <dd className="col-sm-9">{enumLabel(t.channel)}</dd>
            <dt className="col-sm-3">Counter / agent</dt>
            <dd className="col-sm-9">{t.counterName ?? '—'} {t.agentName ? `· ${t.agentName}` : ''}</dd>
            <dt className="col-sm-3">Issued</dt>
            <dd className="col-sm-9">{formatDateTime(t.issuedAt)}</dd>
          </dl>

          {supervise && (
            <div className="d-flex flex-wrap gap-2 mb-3">
              <button type="button" className="btn btn-sm btn-outline-secondary" disabled={!allowed.transfer}
                onClick={() => setAction('transfer')}>
                Transfer
              </button>
              <button type="button" className="btn btn-sm btn-outline-danger" disabled={!allowed.remove}
                onClick={() => setAction('remove')}>
                Remove
              </button>
              {can('TICKET_VOID') && (
                <button type="button" className="btn btn-sm btn-danger" disabled={!allowed.void} onClick={() => setAction('void')}>
                  Void
                </button>
              )}
            </div>
          )}

          <h3 className="h6">Timeline</h3>
          <ol className="list-group list-group-numbered small">
            {t.events.map((e) => (
              <li key={e.id} className="list-group-item">
                <div className="d-flex justify-content-between flex-wrap">
                  <strong>{enumLabel(e.type)}</strong>
                  <time dateTime={e.at} className="text-body-secondary">{formatDateTime(e.at)}</time>
                </div>
                <div>
                  {e.fromStatus && <StatusBadge status={e.fromStatus} />}
                  {e.fromStatus && e.toStatus && ' → '}
                  {e.toStatus && <StatusBadge status={e.toStatus} />}
                  {e.actorName && <> · by {e.actorName}</>}
                  {e.counterName && <> · {e.counterName}</>}
                  {e.siteServiceName && <> · {e.siteServiceName}</>}
                </div>
                {e.reason && <div className="fst-italic">Reason: {e.reason}</div>}
              </li>
            ))}
          </ol>
        </>
      )}

      {t && action === 'remove' && (
        <ReasonDialog title={`Remove ${t.displayNumber}`} variant="danger" confirmLabel="Remove"
          onSubmit={(r) => ticketsApi.remove(t.id, r).then(done)} onClose={() => setAction(null)} />
      )}
      {t && action === 'void' && (
        <ReasonDialog title={`Void ${t.displayNumber}`} variant="danger" confirmLabel="Void"
          onSubmit={(r) => ticketsApi.void(t.id, r).then(done)} onClose={() => setAction(null)} />
      )}
      {t && action === 'transfer' && (
        <ReasonDialog title={`Transfer ${t.displayNumber}`} confirmLabel="Transfer"
          extra={<TransferPicker siteId={siteId} excludeServiceId={t.siteServiceId} value={target} onChange={setTarget} />}
          extraValid={!!target}
          onSubmit={(r) => ticketsApi.transfer(t.id, target, r).then(done)}
          onClose={() => { setAction(null); setTarget(''); }} />
      )}
    </Modal>
  );
}
