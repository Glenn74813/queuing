import { useEffect, useRef, useState } from 'react';
import { queueApi } from '../api/agent';
import { DASHBOARD_SCOPE_KEY, useSite } from '../auth/SiteContext';
import { BarChart } from '../components/BarChart';
import { PieChart, type PieSlice } from '../components/PieChart';
import type { DashboardOverview, ServiceVolume } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { DataTable } from '../components/DataTable';
import { ErrorAlert } from '../components/ErrorAlert';
import { LiveIndicator } from '../components/LiveIndicator';
import { PageHeader } from '../components/PageHeader';
import { NoSiteNotice } from '../components/SiteSelector';
import { Spinner } from '../components/Spinner';
import { useAsync } from '../hooks/useAsync';
import { useSiteQueue } from '../hooks/useSiteQueue';
import { formatMinutes } from '../utils/metrics';

function StatCard({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="col-6 col-lg-3">
      <div className="card stat-card h-100">
        <div className="card-body">
          <div className="text-body-secondary small">{label}</div>
          <div className="value">{value}</div>
        </div>
      </div>
    </div>
  );
}

const PERIODS = [
  { days: 1, label: 'Today' },
  { days: 7, label: 'Last 7 days' },
  { days: 30, label: 'Last 30 days' },
];

const PIE_TOP = 5;

/** Top services by clients as pie slices, the rest folded into one neutral "Other" slice. */
export function toPieSlices(services: ServiceVolume[]): PieSlice[] {
  const detail = (s: { served: number; waiting: number; abandoned: number }) =>
    `${s.served} served · ${s.waiting} in queue · ${s.abandoned} abandoned`;
  const used = [...services].filter((s) => s.issued > 0).sort((a, b) => b.issued - a.issued);
  const top: PieSlice[] = used.slice(0, PIE_TOP).map((s) => ({
    key: s.siteServiceId, label: s.serviceName, value: s.issued, detail: detail(s),
  }));
  const rest = used.slice(PIE_TOP);
  if (rest.length > 0) {
    const sum = (k: 'issued' | 'served' | 'waiting' | 'abandoned') => rest.reduce((n, s) => n + s[k], 0);
    top.push({
      key: 'other', other: true, label: `Other (${rest.length} services)`, value: sum('issued'),
      detail: detail({ served: sum('served'), waiting: sum('waiting'), abandoned: sum('abandoned') }),
    });
  }
  return top;
}

type View = 'pie' | 'bars' | 'table';

/** Clients per service offered at the site; refreshes as tickets are issued and completed. */
function ServicesAvailed({ load, refreshKey }: {
  /** Loads clients per service for the last `days` days (one site, or all sites in scope). */
  load: (days: number) => Promise<{ from: string; to: string; services: ServiceVolume[] }>;
  /** Changes whenever the underlying counts may have changed (triggers a re-fetch). */
  refreshKey: string;
}) {
  const [days, setDays] = useState(1);
  const [view, setView] = useState<View>('pie');
  const volume = useAsync(() => load(days), [days, refreshKey]);
  const v = volume.data;
  const total = v?.services.reduce((n, s) => n + s.issued, 0) ?? 0;
  return (
    <section aria-labelledby="availed-heading" className="mb-4">
      <div className="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
        <h2 id="availed-heading" className="h5 mb-0">Services availed</h2>
        <div className="d-flex align-items-center gap-3">
          <div className="btn-group btn-group-sm" role="group" aria-label="Period">
            {PERIODS.map((p) => (
              <button key={p.days} type="button" className={`btn ${days === p.days ? 'btn-primary' : 'btn-outline-primary'}`}
                aria-pressed={days === p.days} onClick={() => setDays(p.days)}>
                {p.label}
              </button>
            ))}
          </div>
          <div className="btn-group btn-group-sm" role="group" aria-label="Chart type">
            {(['pie', 'bars', 'table'] as View[]).map((k) => (
              <button key={k} type="button" className={`btn ${view === k ? 'btn-secondary' : 'btn-outline-secondary'}`}
                aria-pressed={view === k} onClick={() => setView(k)}>
                {{ pie: 'Pie', bars: 'Bars', table: 'Table' }[k]}
              </button>
            ))}
          </div>
        </div>
      </div>
      <ErrorAlert error={volume.error} />
      {v && (
        <p className="small text-body-secondary mb-2">
          {v.from === v.to ? v.from : `${v.from} to ${v.to}`} · {total.toLocaleString('en-US')} clients across{' '}
          {v.services.length} services · counted by the service chosen at the kiosk; voided tickets excluded
        </p>
      )}
      {v && (view === 'table' ? (
        <DataTable caption="Clients per service" rows={v.services} rowKey={(s) => s.siteServiceId}
          columns={[
            { key: 'serviceName', header: 'Service' },
            { key: 'issued', header: 'Clients', className: 'text-end' },
            { key: 'served', header: 'Served', className: 'text-end' },
            { key: 'waiting', header: 'Still in queue', className: 'text-end' },
            { key: 'abandoned', header: 'Abandoned', className: 'text-end' },
          ]} />
      ) : view === 'pie' ? (
        <div className="card card-body">
          <PieChart title={`Share of clients by service — ${PERIODS.find((p) => p.days === days)?.label.toLowerCase()}`}
            unit="clients" slices={toPieSlices(v.services)} />
          {v.services.filter((s) => s.issued === 0).length > 0 && (
            <p className="small text-body-secondary mb-0 mt-2">
              {v.services.filter((s) => s.issued === 0).length} offered service(s) had no clients; see Bars or Table.
            </p>
          )}
        </div>
      ) : (
        <div className="card card-body">
          <BarChart title={`Clients per service — ${PERIODS.find((p) => p.days === days)?.label.toLowerCase()}`} unit="clients"
            data={v.services.map((s) => ({
              key: s.siteServiceId,
              label: s.serviceName,
              value: s.issued,
              detail: `${s.served} served · ${s.waiting} in queue · ${s.abandoned} abandoned`,
            }))} />
        </div>
      ))}
    </section>
  );
}

/** National catalog volumes in the shape the chart expects (keyed by catalog service). */
const fromCatalog = (o: DashboardOverview) => ({
  from: o.from,
  to: o.to,
  services: o.services.map((s): ServiceVolume => ({
    siteServiceId: s.serviceItemId, serviceCode: s.serviceCode, serviceName: s.serviceName,
    issued: s.issued, served: s.served, abandoned: s.abandoned, waiting: s.waiting,
  })),
});

/** Every site in the user's scope: totals, services availed across sites, and a per-site table. */
function AllSitesDashboard({ onOpenSite }: { onOpenSite: (siteId: string) => void }) {
  const [tick, setTick] = useState(0);
  // No single live topic covers all sites: refresh every 30 seconds instead.
  useEffect(() => {
    const t = window.setInterval(() => setTick((n) => n + 1), 30_000);
    return () => window.clearInterval(t);
  }, []);
  const overview = useAsync(() => queueApi.overview(1), [tick]);
  const o = overview.data;
  return (
    <>
      <ErrorAlert error={overview.error} />
      {overview.loading && !o && <Spinner />}
      {o && (
        <>
          <p className="small text-body-secondary">
            {o.national ? 'All sites nationwide' : 'All sites in your scope'} · {o.siteCount} site{o.siteCount === 1 ? '' : 's'} ·
            today at each site · refreshes every 30 seconds
          </p>
          <section className="row g-3 mb-4" aria-label="Today's statistics, all sites">
            <StatCard label="Issued today" value={o.totals.issuedToday} />
            <StatCard label="Completed today" value={o.totals.completedToday} />
            <StatCard label="Waiting now" value={o.totals.waitingCount} />
            <StatCard label="Avg wait today (min)" value={formatMinutes(o.totals.avgWaitMinutesToday)} />
          </section>
          <ServicesAvailed refreshKey={`all:${tick}`} load={(days) => queueApi.overview(days).then(fromCatalog)} />
          <section aria-labelledby="sites-heading">
            <h2 id="sites-heading" className="h5">Sites</h2>
            <DataTable caption="Today by site" rows={o.sites} rowKey={(s) => s.siteId}
              columns={[
                { key: 'siteName', header: 'Site', render: (s) => (
                  <button type="button" className="btn btn-link p-0 text-start" onClick={() => onOpenSite(s.siteId)}>
                    {s.siteCode} — {s.siteName}
                  </button>
                ) },
                { key: 'regionName', header: 'Region' },
                { key: 'issuedToday', header: 'Issued today', className: 'text-end' },
                { key: 'completedToday', header: 'Completed', className: 'text-end' },
                { key: 'waitingNow', header: 'Waiting now', className: 'text-end' },
                { key: 'avgWaitMinutesToday', header: 'Avg wait (min)', className: 'text-end', render: (s) => formatMinutes(s.avgWaitMinutesToday) },
                { key: 'counters', header: 'Counters staffed', className: 'text-end', render: (s) => `${s.countersStaffed} / ${s.countersTotal}` },
              ]} />
          </section>
        </>
      )}
    </>
  );
}

type Scope = 'all' | 'site';

export function DashboardPage() {
  const { me } = useAuth();
  const { site, siteId, setSiteId } = useSite();
  // National users (and anyone with several sites) start on the all-sites view.
  const multiSite = (me?.sites.length ?? 0) > 1 || !!me?.assignments.some((a) => a.scopeType === 'NATIONAL');
  const isNational = !!me?.assignments.some((a) => a.scopeType === 'NATIONAL');
  // Remembered per tab; picking a site in the header selector switches it to that site (SiteContext).
  const [scope, setScopeState] = useState<Scope>(() => {
    try {
      const stored = sessionStorage.getItem(DASHBOARD_SCOPE_KEY);
      if (stored === 'all' || stored === 'site') return stored;
    } catch {
      /* ignore */
    }
    return multiSite ? 'all' : 'site';
  });
  const setScope = (next: Scope) => {
    setScopeState(next);
    try {
      sessionStorage.setItem(DASHBOARD_SCOPE_KEY, next);
    } catch {
      /* ignore */
    }
  };
  // The header selector can change the site while the Dashboard is open.
  const lastSite = useRef(siteId);
  useEffect(() => {
    if (lastSite.current && siteId && siteId !== lastSite.current) setScopeState('site');
    lastSite.current = siteId;
  }, [siteId]);
  const showAll = multiSite && scope === 'all';
  const { data, error, loading, connected } = useSiteQueue(showAll ? null : siteId);

  const scopeSwitch = multiSite && (
    <div className="btn-group btn-group-sm" role="group" aria-label="Dashboard scope">
      <button type="button" className={`btn ${showAll ? 'btn-primary' : 'btn-outline-primary'}`} aria-pressed={showAll}
        onClick={() => setScope('all')}>
        {isNational ? 'All sites (national)' : 'All my sites'}
      </button>
      <button type="button" className={`btn ${!showAll ? 'btn-primary' : 'btn-outline-primary'}`} aria-pressed={!showAll}
        onClick={() => setScope('site')}>
        {site ? site.name : 'Selected site'}
      </button>
    </div>
  );

  if (showAll) {
    return (
      <>
        <PageHeader title="Dashboard" subtitle={isNational ? 'National overview' : 'All sites in your scope'} actions={scopeSwitch} />
        <AllSitesDashboard onOpenSite={(id) => { setSiteId(id); setScope('site'); }} />
      </>
    );
  }

  if (!siteId) return <NoSiteNotice />;
  return (
    <>
      <PageHeader
        title="Dashboard"
        subtitle={site ? `${site.name}${data ? ` · business date ${data.businessDate}` : ''}` : undefined}
        actions={<div className="d-flex align-items-center gap-2">{scopeSwitch}<LiveIndicator connected={connected} /></div>}
      />
      <ErrorAlert error={error} />
      {loading && !data && <Spinner />}
      {data && (
        <>
          <section className="row g-3 mb-4" aria-label="Today's statistics">
            <StatCard label="Issued today" value={data.stats.issuedToday} />
            <StatCard label="Completed today" value={data.stats.completedToday} />
            <StatCard label="Waiting now" value={data.stats.waitingCount} />
            <StatCard label="Avg wait today (min)" value={formatMinutes(data.stats.avgWaitMinutesToday)} />
          </section>
          <ServicesAvailed refreshKey={`${siteId}:${data.stats.issuedToday}:${data.stats.completedToday}`}
            load={(days) => queueApi.serviceVolume(siteId, days)} />
          <section aria-labelledby="svc-heading">
            <h2 id="svc-heading" className="h5">
              Waiting by service
            </h2>
            <DataTable
              caption="Waiting tickets per service"
              rows={data.services}
              rowKey={(s) => s.siteServiceId}
              columns={[
                { key: 'serviceName', header: 'Service' },
                { key: 'waitingCount', header: 'Waiting', className: 'text-end' },
                {
                  key: 'est',
                  header: 'Est. wait (min)',
                  className: 'text-end',
                  render: (s) => formatMinutes(s.estimatedWaitMinutes),
                },
              ]}
            />
          </section>
          <section aria-labelledby="ctr-heading" className="mt-4">
            <h2 id="ctr-heading" className="h5">
              Counters
            </h2>
            <p className="mb-0">
              {data.counters.filter((c) => c.currentAgent).length} of {data.counters.length} counters staffed ·{' '}
              {data.called.length} being served · {data.held.length} on hold
            </p>
          </section>
        </>
      )}
    </>
  );
}
