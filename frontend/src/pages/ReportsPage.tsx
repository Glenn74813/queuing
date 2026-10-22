import { useState, type FormEvent } from 'react';
import { toApiError } from '../api/client';
import { CHANNELS, CLASSIFICATIONS, CLIENT_TYPES, GROUP_BYS, SERVICE_TYPES } from '../api/enums';
import { orgApi } from '../api/org';
import { reportsApi, type ExportFormat } from '../api/reports';
import type { ApiErrorBody, Channel, Classification, ClientType, GroupBy, ReportParams, ServiceType, SummaryRow } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { DataTable, type Column } from '../components/DataTable';
import { DownloadButton } from '../components/DownloadButton';
import { LineChart } from '../components/LineChart';
import { ErrorAlert } from '../components/ErrorAlert';
import { SelectField, TextField, enumOptions } from '../components/FormFields';
import { PageHeader } from '../components/PageHeader';
import { Spinner } from '../components/Spinner';
import { StatusBadge } from '../components/StatusBadge';
import { useAsync } from '../hooks/useAsync';
import { saveBlob } from '../utils/download';
import { enumLabel, formatDateTime, formatTime, todayIso } from '../utils/format';
import { fillDays, shortDate } from '../utils/trend';
import { METRIC_DEFINITIONS, formatCount, formatMinutes, formatRating, formatSeconds, formatServedRate } from '../utils/metrics';

type Tab = 'summary' | 'trends' | 'agents' | 'tickets' | 'exceptions';

interface FilterForm {
  from: string;
  to: string;
  regionId: string;
  siteId: string;
  clientType: string;
  serviceType: string;
  classification: string;
  channel: string;
  groupBy: GroupBy;
}

function toParams(f: FilterForm): ReportParams {
  return {
    from: f.from,
    to: f.to,
    regionId: f.regionId || undefined,
    siteId: f.siteId || undefined,
    clientType: (f.clientType || undefined) as ClientType | undefined,
    serviceType: (f.serviceType || undefined) as ServiceType | undefined,
    classification: (f.classification || undefined) as Classification | undefined,
    channel: (f.channel || undefined) as Channel | undefined,
    groupBy: f.groupBy,
  };
}

const SUMMARY_COLUMNS: Column<SummaryRow>[] = [
  { key: 'label', header: 'Group' },
  { key: 'issued', header: 'Issued', className: 'text-end', render: (r) => formatCount(r.issued) },
  { key: 'served', header: 'Served', className: 'text-end', render: (r) => formatCount(r.served) },
  { key: 'rate', header: 'Served %', className: 'text-end', render: (r) => formatServedRate(r.served, r.issued) },
  { key: 'abandoned', header: 'Abandoned', className: 'text-end', render: (r) => formatCount(r.abandoned) },
  { key: 'voided', header: 'Voided', className: 'text-end', render: (r) => formatCount(r.voided) },
  { key: 'rerouted', header: 'Rerouted', className: 'text-end', render: (r) => formatCount(r.rerouted) },
  { key: 'avgQueueMinutes', header: 'Avg queue (min)', className: 'text-end', render: (r) => formatMinutes(r.avgQueueMinutes) },
  { key: 'avgWaitMinutes', header: 'Avg wait (min)', className: 'text-end', render: (r) => formatMinutes(r.avgWaitMinutes) },
  { key: 'avgProcessMinutes', header: 'Avg process (min)', className: 'text-end', render: (r) => formatMinutes(r.avgProcessMinutes) },
  { key: 'feedbackCount', header: 'Feedback', className: 'text-end', render: (r) => formatCount(r.feedbackCount) },
  { key: 'avgRating', header: 'Avg rating', className: 'text-end', render: (r) => formatRating(r.avgRating) },
];

function MetricDefinitions() {
  return (
    <details id="metric-definitions" className="card card-body mb-3">
      <summary className="fw-semibold">Metric definitions</summary>
      <dl className="row small mb-0 mt-2">
        {METRIC_DEFINITIONS.map((m) => (
          <div key={m.term} className="col-md-6 d-flex gap-2">
            <dt className="text-nowrap">{m.term}:</dt>
            <dd className="mb-1">{m.definition}</dd>
          </div>
        ))}
      </dl>
      <p className="small text-body-secondary mb-0 mt-2">Averages show “—” when no ticket in the group has the measurement.</p>
    </details>
  );
}

function SummaryTab({ params }: { params: ReportParams }) {
  const report = useAsync(() => reportsApi.summary(params), [params]);
  const r = report.data;
  return (
    <>
      <ErrorAlert error={report.error} />
      {report.loading && !r && <Spinner />}
      {r && (
        <>
          <p className="small text-body-secondary">
            {r.from} to {r.to} · grouped by {enumLabel(r.groupBy)} · generated {formatDateTime(r.generatedAt)} · metric
            definitions v{r.metricDefinitionsVersion}
          </p>
          <DataTable caption="Summary report" rows={r.rows} rowKey={(row) => row.key} columns={SUMMARY_COLUMNS}
            footer={
              <tr className="fw-bold table-light">
                {SUMMARY_COLUMNS.map((c) => (
                  <td key={c.key} className={c.className}>
                    {c.key === 'label' ? 'Total' : c.render?.(r.totals)}
                  </td>
                ))}
              </tr>
            } />
        </>
      )}
    </>
  );
}

/** Daily volume and waiting-time trends for the filtered period (summary grouped by day). */
function TrendsTab({ params }: { params: ReportParams }) {
  const dayParams = { ...params, groupBy: 'DAY' as GroupBy };
  const report = useAsync(() => reportsApi.summary(dayParams), [params]);
  const [asTable, setAsTable] = useState(false);
  const r = report.data;
  const points = r ? fillDays(r.rows, r.from, r.to) : [];
  const dates = points.map((p) => p.date);
  return (
    <>
      <ErrorAlert error={report.error} />
      {report.loading && !r && <Spinner />}
      {r && (
        <>
          <div className="d-flex justify-content-between align-items-center mb-2">
            <p className="small text-body-secondary mb-0">
              {r.from} to {r.to} · one point per business day · days without tickets count as zero
            </p>
            <div className="form-check form-switch mb-0">
              <input id="trend-table" className="form-check-input" type="checkbox" checked={asTable}
                onChange={(e) => setAsTable(e.target.checked)} />
              <label htmlFor="trend-table" className="form-check-label small">Show as table</label>
            </div>
          </div>
          {points.length < 2 && !asTable && (
            <p className="text-body-secondary">Choose a date range of at least two days to see a trend.</p>
          )}
          {asTable ? (
            <DataTable caption="Daily trend" rows={points} rowKey={(p) => p.date}
              columns={[
                { key: 'date', header: 'Date', render: (p) => `${shortDate(p.date)} (${p.date})` },
                { key: 'issued', header: 'Issued', className: 'text-end', render: (p) => formatCount(p.issued) },
                { key: 'served', header: 'Served', className: 'text-end', render: (p) => formatCount(p.served) },
                { key: 'abandoned', header: 'Abandoned', className: 'text-end', render: (p) => formatCount(p.abandoned) },
                { key: 'avgWaitMinutes', header: 'Avg wait (min)', className: 'text-end', render: (p) => formatMinutes(p.avgWaitMinutes) },
              ]} />
          ) : points.length >= 2 && (
            <div className="row">
              <div className="col-xl-7">
                <LineChart title="Clients per day" dates={dates} formatValue={(v) => formatCount(v)}
                  series={[
                    { key: 'issued', label: 'Issued', slot: 1, values: points.map((p) => p.issued) },
                    { key: 'served', label: 'Served', slot: 2, values: points.map((p) => p.served) },
                    { key: 'abandoned', label: 'Abandoned', slot: 3, values: points.map((p) => p.abandoned) },
                  ]} />
              </div>
              <div className="col-xl-5">
                <LineChart title="Average waiting time per day (minutes)" dates={dates} formatValue={(v) => `${v.toFixed(1)} min`}
                  series={[{ key: 'wait', label: 'Avg wait', slot: 1, values: points.map((p) => p.avgWaitMinutes) }]} />
              </div>
            </div>
          )}
        </>
      )}
    </>
  );
}

const pct = (v?: number) => (v == null ? '—' : `${v.toFixed(1)}%`);

/** Each agent measured against the SLA of their site's region (Admin › SLA Targets). */
function AgentPerformanceTab({ params }: { params: ReportParams }) {
  const report = useAsync(() => reportsApi.agentPerformance(params), [params]);
  const { can } = useAuth();
  const r = report.data;
  return (
    <>
      <div className="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-2">
        <p className="small text-body-secondary mb-0" style={{ maxWidth: '52rem' }}>
          Targets come from the SLA of each site's region (national default when the region has none; service
          overrides apply where set). <strong>SLA met</strong> when the share of transactions processed within target
          reaches the compliance target. Processing counts only the agent's own time at the counter (holds excluded);
          waiting is the client's time in the queue before this agent's call. Region and site filters apply; the
          other filters do not. <strong>Download Excel</strong> gives a workbook of every agent in scope (including
          those with no activity), with daily breakdown, each transaction, and the SLA targets used.
        </p>
        {can('REPORT_EXPORT') && (
          <div className="d-flex align-items-center gap-2" role="group" aria-label="Download agent performance">
            <span className="small text-body-secondary">Agent performance:</span>
            <DownloadButton label="Download Excel (all agents)" className="btn btn-sm btn-primary"
              download={() => reportsApi.exportAgentPerformance(params, 'xlsx')} />
            {(['csv', 'pdf'] as const).map((f) => (
              <DownloadButton key={f} label={`Download ${f.toUpperCase()}`} className="btn btn-sm btn-outline-primary"
                download={() => reportsApi.exportAgentPerformance(params, f)} />
            ))}
          </div>
        )}
      </div>
      <ErrorAlert error={report.error} />
      <DataTable caption="Agent performance against SLA" loading={report.loading} rows={r?.rows} rowKey={(x) => `${x.agentId}:${x.siteId}`}
        emptyText="No agent activity in this period."
        columns={[
          { key: 'agentName', header: 'Agent', render: (x) => (
            <>
              {x.agentName}
              {x.username && <div className="small text-body-secondary">{x.username}</div>}
            </>
          ) },
          { key: 'siteName', header: 'Site', render: (x) => (
            <>
              {x.siteName}
              <div className="small text-body-secondary">{x.regionName}</div>
            </>
          ) },
          { key: 'handled', header: 'Handled', className: 'text-end', render: (x) => formatCount(x.handled) },
          { key: 'completed', header: 'Completed', className: 'text-end', render: (x) => formatCount(x.completed) },
          { key: 'transferred', header: 'Transferred', className: 'text-end', render: (x) => formatCount(x.transferred) },
          { key: 'noShows', header: 'No-shows', className: 'text-end', render: (x) => formatCount(x.noShows) },
          { key: 'recalls', header: 'Recalls', className: 'text-end', render: (x) => formatCount(x.recalls) },
          { key: 'avgProcessingMinutes', header: 'Avg process (min)', className: 'text-end', render: (x) => formatMinutes(x.avgProcessingMinutes) },
          { key: 'proc', header: 'Within process target', className: 'text-end', render: (x) => (
            <>
              {pct(x.processingWithinTargetPercent)}
              <div className="small text-body-secondary">target {x.processingTargetMinutes ?? '—'} min{x.serviceOverrides ? '*' : ''}</div>
            </>
          ) },
          { key: 'avgWaitMinutes', header: 'Avg wait (min)', className: 'text-end', render: (x) => formatMinutes(x.avgWaitMinutes) },
          { key: 'wait', header: 'Within wait target', className: 'text-end', render: (x) => (
            <>
              {pct(x.waitingWithinTargetPercent)}
              <div className="small text-body-secondary">target {x.waitingTargetMinutes ?? '—'} min</div>
            </>
          ) },
          { key: 'sla', header: 'SLA', render: (x) => (
            <>
              <StatusBadge status={x.slaStatus} />
              {x.complianceTargetPercent != null && <div className="small text-body-secondary">needs {x.complianceTargetPercent}%</div>}
            </>
          ) },
        ]} />
      {r?.rows.some((x) => x.serviceOverrides) && (
        <p className="small text-body-secondary mt-2">* Some services have their own targets in this region's SLA.</p>
      )}
    </>
  );
}

function TicketsTab({ params }: { params: ReportParams }) {
  const [page, setPage] = useState(0);
  const rows = useAsync(() => reportsApi.tickets({ ...params, page, size: 50 }), [params, page]);
  const { can } = useAuth();
  return (
    <>
      {can('REPORT_EXPORT') && (
        <div className="d-flex justify-content-end mb-2">
          <DownloadButton label="Export all rows (CSV)" className="btn btn-sm btn-outline-primary"
            download={() => reportsApi.exportTickets(params)} />
        </div>
      )}
      <ErrorAlert error={rows.error} />
      <DataTable caption="Ticket-level report" loading={rows.loading} rows={rows.data?.content} rowKey={(t) => t.id}
        paging={rows.data && { page: rows.data.page, totalPages: rows.data.totalPages, totalElements: rows.data.totalElements, onPageChange: setPage }}
        columns={[
          { key: 'businessDate', header: 'Date' },
          { key: 'displayNumber', header: 'Ticket' },
          { key: 'siteName', header: 'Site' },
          { key: 'serviceName', header: 'Service' },
          { key: 'channel', header: 'Channel', render: (t) => enumLabel(t.channel) },
          { key: 'status', header: 'Status', render: (t) => <StatusBadge status={t.status} /> },
          { key: 'issuedAt', header: 'Issued', render: (t) => formatTime(t.issuedAt) },
          { key: 'queueSeconds', header: 'Queue', className: 'text-end', render: (t) => formatSeconds(t.queueSeconds) },
          { key: 'waitSeconds', header: 'Wait', className: 'text-end', render: (t) => formatSeconds(t.waitSeconds) },
          { key: 'serviceSeconds', header: 'Process', className: 'text-end', render: (t) => formatSeconds(t.serviceSeconds) },
          { key: 'holdSeconds', header: 'Hold', className: 'text-end', render: (t) => formatSeconds(t.holdSeconds) },
          { key: 'transferCount', header: 'Transfers', className: 'text-end' },
          { key: 'deviceCode', header: 'Device', render: (t) => t.deviceCode ?? '—' },
        ]} />
    </>
  );
}

function ExceptionsTab({ params }: { params: ReportParams }) {
  const rows = useAsync(() => reportsApi.exceptions(params), [params]);
  return (
    <>
      <p className="small text-body-secondary">Tickets with inconsistent or incomplete data that are excluded from or flagged in the averages.</p>
      <ErrorAlert error={rows.error} />
      <DataTable caption="Data exceptions" loading={rows.loading} rows={rows.data} rowKey={(r) => `${r.ticketId}-${r.issue}`}
        emptyText="No data exceptions for this period."
        columns={[
          { key: 'businessDate', header: 'Date' },
          { key: 'displayNumber', header: 'Ticket' },
          { key: 'siteName', header: 'Site' },
          { key: 'issue', header: 'Issue' },
        ]} />
    </>
  );
}

export function ReportsPage() {
  const { me, can } = useAuth();
  const [form, setForm] = useState<FilterForm>({
    from: todayIso(-7), to: todayIso(), regionId: '', siteId: '', clientType: '', serviceType: '', classification: '', channel: '', groupBy: 'SITE',
  });
  const [params, setParams] = useState<ReportParams | null>(null);
  const [tab, setTab] = useState<Tab>('summary');
  const [exporting, setExporting] = useState<ExportFormat | null>(null);
  const [exportError, setExportError] = useState<ApiErrorBody | null>(null);
  // Region/site lists are best-effort: report users may not be able to list organization data.
  const regions = useAsync(() => orgApi.listRegions(), []);
  const sites = useAsync(() => orgApi.listSites(form.regionId || undefined), [form.regionId]);
  const siteOptions = (sites.data ?? me?.sites ?? []).map((s) => ({ value: s.id, label: `${s.code} — ${s.name}` }));
  const set = (k: keyof FilterForm) => (v: string) => setForm((p) => ({ ...p, [k]: v, ...(k === 'regionId' ? { siteId: '' } : {}) }));

  const run = (e: FormEvent) => {
    e.preventDefault();
    if (!form.from || !form.to || form.to < form.from) return;
    setParams(toParams(form));
  };

  const doExport = async (format: ExportFormat) => {
    if (!params) return;
    setExporting(format);
    setExportError(null);
    try {
      const { blob, filename } = await reportsApi.exportSummary(params, format);
      saveBlob(blob, filename);
    } catch (e) {
      setExportError(toApiError(e));
    } finally {
      setExporting(null);
    }
  };

  const rangeInvalid = !!form.from && !!form.to && form.to < form.from;

  return (
    <>
      <PageHeader title="Reports" subtitle={<a href="#metric-definitions">What do the columns mean?</a>}
        actions={can('REPORT_EXPORT') && params && (
          <>
            <button type="button" className="btn btn-outline-primary" disabled={!!exporting} onClick={() => void doExport('csv')}>
              {exporting === 'csv' ? 'Exporting…' : 'Export CSV'}
            </button>
            <button type="button" className="btn btn-outline-primary" disabled={!!exporting} onClick={() => void doExport('xlsx')}>
              {exporting === 'xlsx' ? 'Exporting…' : 'Export XLSX'}
            </button>
            <button type="button" className="btn btn-outline-primary" disabled={!!exporting} onClick={() => void doExport('pdf')}>
              {exporting === 'pdf' ? 'Exporting…' : 'Export PDF'}
            </button>
          </>
        )} />

      <form className="card card-body mb-3" onSubmit={run} aria-label="Report filters">
        <div className="row g-2">
          <TextField className="col-6 col-md-3 col-xl-2" label="From" type="date" required value={form.from} onChange={set('from')} />
          <TextField className="col-6 col-md-3 col-xl-2" label="To (inclusive)" type="date" required value={form.to} onChange={set('to')}
            error={rangeInvalid ? 'Must be on or after From' : undefined} />
          <SelectField className="col-6 col-md-3 col-xl-2" label="Region" value={form.regionId} onChange={set('regionId')} placeholder="All"
            options={(regions.data ?? []).map((r) => ({ value: r.id, label: r.name }))} />
          <SelectField className="col-6 col-md-3 col-xl-2" label="Site" value={form.siteId} onChange={set('siteId')} placeholder="All" options={siteOptions} />
          <SelectField className="col-6 col-md-3 col-xl-2" label="Group by" value={form.groupBy} onChange={(v) => set('groupBy')(v)} options={enumOptions(GROUP_BYS)} />
          <SelectField className="col-6 col-md-3 col-xl-2" label="Client type" value={form.clientType} onChange={set('clientType')} placeholder="All" options={enumOptions(CLIENT_TYPES)} />
          <SelectField className="col-6 col-md-3 col-xl-2" label="Service type" value={form.serviceType} onChange={set('serviceType')} placeholder="All" options={enumOptions(SERVICE_TYPES)} />
          <SelectField className="col-6 col-md-3 col-xl-2" label="Classification" value={form.classification} onChange={set('classification')} placeholder="All" options={enumOptions(CLASSIFICATIONS)} />
          <SelectField className="col-6 col-md-3 col-xl-2" label="Channel" value={form.channel} onChange={set('channel')} placeholder="All" options={enumOptions(CHANNELS)} />
          <div className="col-12 col-md-3 col-xl-2 d-flex align-items-end mb-3">
            <button type="submit" className="btn btn-primary w-100" disabled={rangeInvalid}>Run report</button>
          </div>
        </div>
      </form>

      <MetricDefinitions />
      <ErrorAlert error={exportError} onDismiss={() => setExportError(null)} />

      {!params ? (
        <p className="text-body-secondary">Choose filters and select “Run report”.</p>
      ) : (
        <>
          <ul className="nav nav-tabs mb-3" role="tablist">
            {(['summary', 'trends', 'agents', 'tickets', 'exceptions'] as Tab[]).map((t) => (
              <li key={t} className="nav-item" role="presentation">
                <button type="button" role="tab" aria-selected={tab === t} className={`nav-link ${tab === t ? 'active' : ''}`} onClick={() => setTab(t)}>
                  {{ summary: 'Summary', trends: 'Trends', agents: 'Agent performance', tickets: 'Ticket details', exceptions: 'Data exceptions' }[t]}
                </button>
              </li>
            ))}
          </ul>
          <div role="tabpanel">
            {tab === 'summary' && <SummaryTab params={params} />}
            {tab === 'trends' && <TrendsTab params={params} />}
            {tab === 'agents' && <AgentPerformanceTab params={params} />}
            {tab === 'tickets' && <TicketsTab params={params} />}
            {tab === 'exceptions' && <ExceptionsTab params={params} />}
          </div>
        </>
      )}
    </>
  );
}
