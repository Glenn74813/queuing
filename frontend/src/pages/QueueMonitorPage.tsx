import { useState } from 'react';
import { countersApi } from '../api/counters';
import type { Counter, Ticket } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useSite } from '../auth/SiteContext';
import { DataTable, type Column } from '../components/DataTable';
import { ErrorAlert } from '../components/ErrorAlert';
import { LiveIndicator } from '../components/LiveIndicator';
import { PageHeader } from '../components/PageHeader';
import { ReasonDialog } from '../components/ReasonDialog';
import { NoSiteNotice } from '../components/SiteSelector';
import { StatusBadge } from '../components/StatusBadge';
import { TicketDetailModal } from '../components/TicketDetailModal';
import { useSiteQueue } from '../hooks/useSiteQueue';
import { formatTime } from '../utils/format';

/** Supervisor's live view of a site's queue. Row click opens the ticket detail/timeline. */
export function QueueMonitorPage() {
  const { site, siteId } = useSite();
  const { data, error, loading, connected, reload } = useSiteQueue(siteId);
  const [selected, setSelected] = useState<string | null>(null);
  const [closing, setClosing] = useState<Counter | null>(null);
  const { can } = useAuth();

  if (!siteId) return <NoSiteNotice />;

  const detailBtn = (t: Ticket) => (
    <button type="button" className="btn btn-link btn-sm p-0 fw-bold" onClick={() => setSelected(t.id)}>
      {t.displayNumber}
    </button>
  );
  const base: Column<Ticket>[] = [
    { key: 'displayNumber', header: 'Ticket', render: detailBtn },
    { key: 'serviceName', header: 'Service' },
    { key: 'priorityName', header: 'Priority', render: (t) => t.priorityName ?? '—' },
  ];

  return (
    <>
      <PageHeader title="Queue Monitor" subtitle={site?.name} actions={<LiveIndicator connected={connected} />} />
      <ErrorAlert error={error} />
      <div className="row g-3">
        <section className="col-xl-4 col-lg-6" aria-labelledby="w-h">
          <h2 id="w-h" className="h5">Waiting ({data?.waiting.length ?? 0})</h2>
          <DataTable caption="Waiting tickets" loading={loading} rows={data?.waiting} rowKey={(t) => t.id}
            columns={[...base, { key: 'issuedAt', header: 'Issued', render: (t) => formatTime(t.issuedAt) },
              { key: 'stageNo', header: 'Stage', className: 'text-end' }]} />
        </section>
        <section className="col-xl-4 col-lg-6" aria-labelledby="c-h">
          <h2 id="c-h" className="h5">Being served ({data?.called.length ?? 0})</h2>
          <DataTable caption="Called tickets" loading={loading} rows={data?.called} rowKey={(t) => t.id}
            columns={[...base, { key: 'counterName', header: 'Counter', render: (t) => t.counterName ?? '—' },
              { key: 'lastCalledAt', header: 'Called', render: (t) => formatTime(t.lastCalledAt) }]} />
        </section>
        <section className="col-xl-4 col-lg-6" aria-labelledby="h-h">
          <h2 id="h-h" className="h5">On hold ({data?.held.length ?? 0})</h2>
          <DataTable caption="Held tickets" loading={loading} rows={data?.held} rowKey={(t) => t.id}
            columns={[...base, { key: 'counterName', header: 'Counter', render: (t) => t.counterName ?? '—' }]} />
        </section>
        <section className="col-xl-12 col-lg-6" aria-labelledby="ctr-h">
          <h2 id="ctr-h" className="h5">Counters</h2>
          <DataTable caption="Counters and agents" loading={loading} rows={data?.counters} rowKey={(c) => c.id}
            columns={[
              { key: 'name', header: 'Counter', render: (c) => `${c.name} (${c.code})` },
              { key: 'agent', header: 'Agent', render: (c) => c.currentAgent?.fullName ?? <span className="text-body-secondary">Unstaffed</span> },
              { key: 'agentStatus', header: 'Status', render: (c) => (c.currentAgent ? <StatusBadge status={c.agentStatus} /> : '—') },
              { key: 'serving', header: 'Serving', render: (c) => data?.called.find((t) => t.counterId === c.id)?.displayNumber ?? '—' },
              ...(can('QUEUE_SUPERVISE') ? [{
                key: 'close', header: <span className="visually-hidden">Actions</span>, className: 'text-end',
                render: (c: Counter) => c.currentAgent && (
                  <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setClosing(c)}>Close counter</button>
                ),
              }] : []),
            ]} />
        </section>
      </div>
      {closing && (
        <ReasonDialog title={`Close ${closing.name}`} confirmLabel="Close counter" variant="danger"
          description={`Signs ${closing.currentAgent?.fullName ?? 'the agent'} out of this counter. Any ticket being served or on hold there goes back to its queue at its original place.`}
          onSubmit={(reason) => countersApi.forceEndSession(siteId, closing.id, reason).then(() => reload())}
          onClose={() => setClosing(null)} />
      )}
      {selected && (
        <TicketDetailModal ticketId={selected} siteId={siteId} onClose={() => setSelected(null)} onChanged={() => void reload()} />
      )}
    </>
  );
}
