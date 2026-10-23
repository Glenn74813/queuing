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
import { KpiCard, Section } from '../components/Section';
import { NoSiteNotice } from '../components/SiteSelector';
import { Spinner } from '../components/Spinner';
import { useAsync } from '../hooks/useAsync';
import { useSiteQueue } from '../hooks/useSiteQueue';
import { formatBusinessDate, formatDateRange } from '../utils/format';
import { formatMinutes } from '../utils/metrics';

const fmtNum = (n: number) => n.toLocaleString('en-US');
const pctOf = (part: number, whole: number) => (whole > 0 ? `${Math.round((part / whole) * 100)}% of issued` : undefined);

/** The four headline numbers shared by the national and site views. */
function TodayKpis({ issued, completed, waiting, avgWait, label }: {
  issued: number; completed: number; waiting: number; avgWait: number | null | undefined; label: string;
}) {
  const avg = formatMinutes(avgWait);
  return (
    <section className="row g-3 mb-4" aria-label={label}>
      <KpiCard label="Tickets issued" value={fmtNum(issued)} caption="Today" />
      <KpiCard label="Completed" value={fmtNum(completed)} caption={pctOf(completed, issued) ?? 'Today'} />
      <KpiCard label="Waiting now" value={fmtNum(waiting)} caption="In queue at this moment" />
      <KpiCard label="Average wait" value={avg} unit="min" caption="Issue to first call, today" />
    </section>
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
  const unused = v?.services.filter((s) => s.issued === 0).length ?? 0;
  const periodLabel = PERIODS.find((p) => p.days === days)?.label.toLowerCase();
  return (
    <Section id="availed-heading" title="Services availed"
      description={v ? `${formatDateRange(v.from, v.to)} · ${fmtNum(total)} client${total === 1 ? '' : 's'} across ${v.services.length} services` : undefined}
      actions={(
        <>
          <div className="btn-group btn-group-sm segmented" role="group" aria-label="Period">
            {PERIODS.map((p) => (
              <button key={p.days} type="button" className={`btn ${days === p.days ? 'btn-primary' : 'btn-outline-primary'}`}
                aria-pressed={days === p.days} onClick={() => setDays(p.days)}>
                {p.label}
              </button>
            ))}
          </div>
          <div className="btn-group btn-group-sm segmented" role="group" aria-label="Chart type">
            {(['pie', 'bars', 'table'] as View[]).map((k) => (
              <button key={k} type="button" className={`btn ${view === k ? 'btn-secondary' : 'btn-outline-secondary'}`}
                aria-pressed={view === k} onClick={() => setView(k)}>
                {{ pie: 'Pie', bars: 'Bars', table: 'Table' }[k]}
              </button>
            ))}
          </div>
        </>
      )}>
      <ErrorAlert error={volume.error} />
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
        <div className="surface surface-body">
          {total === 0 ? (
            <div className="empty-state" role="status">
              <div className="empty-state-title">No clients {days === 1 ? 'yet today' : `in the ${periodLabel}`}</div>
              <div className="small">The share of clients per service appears here as tickets are issued at the kiosks.</div>
            </div>
          ) : (
            <PieChart title={`Share of clients by service — ${periodLabel}`} unit="clients" slices={toPieSlices(v.services)} />
          )}
          <p className="footnote">
            Counted by the service chosen at the kiosk; voided tickets are excluded.
            {total > 0 && unused > 0 && ` ${unused} offered service${unused === 1 ? '' : 's'} had no clients (see Bars or Table).`}
          </p>
        </div>
      ) : (
        <div className="surface surface-body">
          <BarChart title={`Clients per service — ${periodLabel}`} unit="clients"
            data={v.services.map((s) => ({
              key: s.siteServiceId,
              label: s.serviceName,
              value: s.issued,
              detail: `${s.served} served · ${s.waiting} in queue · ${s.abandoned} abandoned`,
            }))} />
          <p className="footnote">Counted by the service chosen at the kiosk; voided tickets are excluded.</p>
        </div>
      ))}
    </Section>
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
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  useEffect(() => {
    if (o) setUpdatedAt(new Date());
  }, [o]);
  return (
    <>
      <ErrorAlert error={overview.error} />
      {overview.loading && !o && <Spinner />}
      {o && (
        <>
          <div className="meta-row">
            <span><strong>{o.national ? 'All sites nationwide' : 'All sites in your scope'}</strong></span>
            <span>{o.siteCount} site{o.siteCount === 1 ? '' : 's'}</span>
            <span>Today, each site's local date</span>
            {updatedAt && <span>Updated {updatedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}, refreshes every 30 s</span>}
          </div>
          <TodayKpis label="Today's statistics, all sites" issued={o.totals.issuedToday} completed={o.totals.completedToday}
            waiting={o.totals.waitingCount} avgWait={o.totals.avgWaitMinutesToday} />
          <ServicesAvailed refreshKey={`all:${tick}`} load={(days) => queueApi.overview(days).then(fromCatalog)} />
          <Section id="sites-heading" title="Sites" description="Today at each site. Select a site to open its live view.">
            <DataTable caption="Today by site" rows={o.sites} rowKey={(s) => s.siteId}
              columns={[
                { key: 'siteName', header: 'Site', className: 'text-nowrap', render: (s) => (
                  <button type="button" className="btn btn-link p-0 text-start link-strong" onClick={() => onOpenSite(s.siteId)}>
                    <span className="code-tag">{s.siteCode}</span>{s.siteName}
                  </button>
                ) },
                { key: 'regionName', header: 'Region', className: 'text-nowrap text-body-secondary' },
                { key: 'issuedToday', header: 'Issued', className: 'text-end', render: (s) => fmtNum(s.issuedToday) },
                { key: 'completedToday', header: 'Completed', className: 'text-end', render: (s) => fmtNum(s.completedToday) },
                { key: 'waitingNow', header: 'Waiting', className: 'text-end', render: (s) => fmtNum(s.waitingNow) },
                { key: 'avgWaitMinutesToday', header: 'Avg wait (min)', className: 'text-end', render: (s) => formatMinutes(s.avgWaitMinutesToday) },
                { key: 'counters', header: 'Counters staffed', className: 'text-end text-nowrap', render: (s) => (
                  <>
                    {s.countersStaffed} of {s.countersTotal}
                    <span className="ratio-bar" aria-hidden="true">
                      <span style={{ width: `${s.countersTotal > 0 ? (s.countersStaffed / s.countersTotal) * 100 : 0}%` }} />
                    </span>
                  </>
                ) },
              ]} />
          </Section>
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
    <div className="btn-group btn-group-sm segmented" role="group" aria-label="Dashboard scope">
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
        subtitle={site ? `${site.name}${data ? ` · ${formatBusinessDate(data.businessDate)}` : ''}` : undefined}
        actions={<div className="d-flex align-items-center gap-2">{scopeSwitch}<LiveIndicator connected={connected} /></div>}
      />
      <ErrorAlert error={error} />
      {loading && !data && <Spinner />}
      {data && (
        <>
          <TodayKpis label="Today's statistics" issued={data.stats.issuedToday} completed={data.stats.completedToday}
            waiting={data.stats.waitingCount} avgWait={data.stats.avgWaitMinutesToday} />
          <ServicesAvailed refreshKey={`${siteId}:${data.stats.issuedToday}:${data.stats.completedToday}`}
            load={(days) => queueApi.serviceVolume(siteId, days)} />
          <Section id="svc-heading" title="Waiting by service" description="Clients in each line now and the estimated wait for a new ticket.">
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
          </Section>
          <Section id="ctr-heading" title="Counters">
            <div className="row g-3">
              <KpiCard label="Staffed" value={`${data.counters.filter((c) => c.currentAgent).length} of ${data.counters.length}`} caption="Counters with an agent signed in" />
              <KpiCard label="Being served" value={fmtNum(data.called.length)} caption="Clients at a counter now" />
              <KpiCard label="On hold" value={fmtNum(data.held.length)} caption="Waiting for documents or a return" />
            </div>
          </Section>
        </>
      )}
    </>
  );
}
