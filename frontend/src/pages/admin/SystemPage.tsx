import { useState } from 'react';
import { toApiError } from '../../api/client';
import type { ApiErrorBody, OutboxStatus } from '../../api/types';
import { systemApi } from '../../api/system';
import { useAuth } from '../../auth/AuthContext';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { SelectField, TextField, enumOptions } from '../../components/FormFields';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime, todayIso } from '../../utils/format';

function StatusPanel() {
  const status = useAsync(() => systemApi.status(), []);
  const s = status.data;
  return (
    <section className="card mb-4" aria-labelledby="sys-h">
      <div className="card-body">
        <div className="d-flex justify-content-between">
          <h2 id="sys-h" className="h5">Node status</h2>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => void status.reload()}>Refresh</button>
        </div>
        <ErrorAlert error={status.error} />
        {!s ? (
          status.loading && <Spinner />
        ) : (
          <dl className="row mb-0">
            <dt className="col-sm-4">Node role</dt>
            <dd className="col-sm-8">{s.nodeRole}{s.siteCode ? ` (${s.siteCode})` : ''}</dd>
            <dt className="col-sm-4">Version</dt>
            <dd className="col-sm-8">{s.version}</dd>
            {s.centralUrl && (
              <>
                <dt className="col-sm-4">Central URL</dt>
                <dd className="col-sm-8">{s.centralUrl}</dd>
              </>
            )}
            <dt className="col-sm-4">Outbox</dt>
            <dd className="col-sm-8">
              {s.outbox.pending} pending · <span className={s.outbox.failed > 0 ? 'text-danger fw-bold' : ''}>{s.outbox.failed} failed</span>
              {s.outbox.oldestPendingAt && ` · oldest pending ${formatDateTime(s.outbox.oldestPendingAt)}`}
            </dd>
            <dt className="col-sm-4">Last successful push</dt>
            <dd className="col-sm-8">{formatDateTime(s.lastSuccessfulPushAt)}</dd>
            <dt className="col-sm-4">Last config pull</dt>
            <dd className="col-sm-8">{formatDateTime(s.lastConfigPullAt)}</dd>
            <dt className="col-sm-4">Server time</dt>
            <dd className="col-sm-8">{formatDateTime(s.serverTime)}</dd>
          </dl>
        )}
      </div>
    </section>
  );
}

function OutboxPanel() {
  const [status, setStatus] = useState<OutboxStatus | ''>('FAILED');
  const [page, setPage] = useState(0);
  const outbox = useAsync(() => systemApi.outbox({ status, page, size: 50 }), [status, page]);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [error, setError] = useState<ApiErrorBody | null>(null);

  const retry = async (id: string) => {
    setRetrying(id);
    setError(null);
    try {
      await systemApi.retryOutbox(id);
      await outbox.reload();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setRetrying(null);
    }
  };

  return (
    <section aria-labelledby="outbox-h">
      <div className="d-flex flex-wrap justify-content-between align-items-end gap-2 mb-2">
        <h2 id="outbox-h" className="h5 mb-0">Sync outbox</h2>
        <SelectField className="mb-0" label="Status" value={status} placeholder="All"
          onChange={(v) => { setStatus(v as OutboxStatus | ''); setPage(0); }} options={enumOptions(['PENDING', 'SENT', 'FAILED'])} />
      </div>
      <ErrorAlert error={error ?? outbox.error} />
      <DataTable caption="Outbox entries" loading={outbox.loading} rows={outbox.data?.content} rowKey={(o) => o.id}
        paging={outbox.data && { page: outbox.data.page, totalPages: outbox.data.totalPages, totalElements: outbox.data.totalElements, onPageChange: setPage }}
        columns={[
          { key: 'createdAt', header: 'Created', render: (o) => formatDateTime(o.createdAt) },
          { key: 'eventType', header: 'Event' },
          { key: 'aggregate', header: 'Aggregate', render: (o) => `${o.aggregateType} ${o.aggregateId}` },
          { key: 'status', header: 'Status', render: (o) => <StatusBadge status={o.status} /> },
          { key: 'attempts', header: 'Attempts', className: 'text-end' },
          { key: 'nextAttemptAt', header: 'Next attempt', render: (o) => formatDateTime(o.nextAttemptAt) },
          { key: 'lastError', header: 'Last error', render: (o) => <span className="small text-break">{o.lastError ?? '—'}</span> },
          { key: 'retry', header: <span className="visually-hidden">Actions</span>, className: 'text-end',
            render: (o) => o.status !== 'SENT' && (
              <button type="button" className="btn btn-sm btn-outline-primary" disabled={retrying === o.id} onClick={() => void retry(o.id)}>
                {retrying === o.id ? 'Retrying…' : 'Retry'}
              </button>
            ) },
        ]} />
    </section>
  );
}

/** Shows "site / central" counts, highlighting differences. */
function Counts({ site, central }: { site: number; central: number }) {
  return <span className={site !== central ? 'text-danger fw-bold' : ''}>{site} / {central}</span>;
}

/**
 * Central node: compares each site's self-reported daily counts with what central received.
 * Site node: lets an administrator send the daily summaries immediately.
 */
function ReconciliationPanel() {
  const status = useAsync(() => systemApi.status(), []);
  const [from, setFrom] = useState(todayIso(-6));
  const [to, setTo] = useState(todayIso());
  const [onlyProblems, setOnlyProblems] = useState(true);
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);
  const isCentral = status.data?.nodeRole === 'CENTRAL';
  const rows = useAsync(
    () => (isCentral && from && to && from <= to ? systemApi.reconciliation({ from, to, onlyProblems }) : Promise.resolve(null)),
    [isCentral, from, to, onlyProblems],
  );

  const sendNow = async () => {
    setSending(true);
    setError(null);
    setSent(false);
    try {
      await systemApi.reportReconciliationNow();
      setSent(true);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setSending(false);
    }
  };

  if (!status.data) return null;
  return (
    <section className="mt-4" aria-labelledby="recon-h">
      <h2 id="recon-h" className="h5">Sync reconciliation</h2>
      <ErrorAlert error={error ?? rows.error} />
      {!isCentral ? (
        <div className="card card-body">
          <p className="mb-2">
            This site reports its daily ticket, event and feedback counts to central every hour, so that lost or stuck
            records can be detected there.
          </p>
          <div>
            <button type="button" className="btn btn-outline-primary" disabled={sending} onClick={() => void sendNow()}>
              {sending ? 'Queuing…' : 'Send summaries now'}
            </button>
            {sent && <span className="ms-2 text-success" role="status">Queued for the next sync push.</span>}
          </div>
        </div>
      ) : (
        <>
          <div className="d-flex flex-wrap align-items-end gap-3 mb-2">
            <TextField className="mb-0" label="From" type="date" value={from} onChange={setFrom} />
            <TextField className="mb-0" label="To" type="date" value={to} onChange={setTo} />
            <div className="form-check mb-2">
              <input id="recon-problems" className="form-check-input" type="checkbox" checked={onlyProblems}
                onChange={(e) => setOnlyProblems(e.target.checked)} />
              <label htmlFor="recon-problems" className="form-check-label">Only differences</label>
            </div>
          </div>
          <p className="small text-body-secondary">
            Counts are shown as site / central. IN TRANSIT: central is behind but the site still had unsent records.
            MISMATCH: records are missing with nothing left to send, or central holds more than the site.
          </p>
          <DataTable caption="Reconciliation by site and day" loading={rows.loading} rows={rows.data ?? undefined}
            rowKey={(r) => `${r.siteId}:${r.businessDate}`}
            columns={[
              { key: 'businessDate', header: 'Date' },
              { key: 'site', header: 'Site', render: (r) => `${r.siteCode} — ${r.siteName}` },
              { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
              { key: 'tickets', header: 'Tickets', className: 'text-end', render: (r) => <Counts site={r.siteTickets} central={r.centralTickets} /> },
              { key: 'events', header: 'Events', className: 'text-end', render: (r) => <Counts site={r.siteEvents} central={r.centralEvents} /> },
              { key: 'feedback', header: 'Feedback', className: 'text-end', render: (r) => <Counts site={r.siteFeedback} central={r.centralFeedback} /> },
              { key: 'siteOutboxPending', header: 'Site backlog', className: 'text-end' },
              { key: 'reportedAt', header: 'Reported', render: (r) => formatDateTime(r.reportedAt) },
            ]} />
        </>
      )}
    </section>
  );
}

export function SystemPage() {
  const { can } = useAuth();
  return (
    <>
      <PageHeader title="System & Sync" />
      {can('SYSTEM_MONITOR') && <StatusPanel />}
      {can('SYNC_MANAGE') && <OutboxPanel />}
      {can('SYNC_MANAGE') && can('SYSTEM_MONITOR') && <ReconciliationPanel />}
    </>
  );
}
