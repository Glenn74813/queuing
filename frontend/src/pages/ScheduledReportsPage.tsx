import { useState } from 'react';
import { toApiError } from '../api/client';
import { GROUP_BYS } from '../api/enums';
import { orgApi } from '../api/org';
import { reportSchedulesApi } from '../api/reports';
import type {
  ApiErrorBody,
  GroupBy,
  ReportSchedule,
  ReportScheduleInput,
  ScheduleFormat,
  ScheduleFrequency,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { DataTable } from '../components/DataTable';
import { DownloadButton } from '../components/DownloadButton';
import { ErrorAlert } from '../components/ErrorAlert';
import { CheckField, SelectField, TextField, enumOptions } from '../components/FormFields';
import { FormModal } from '../components/FormModal';
import { Modal } from '../components/Modal';
import { PageHeader } from '../components/PageHeader';
import { StatusBadge } from '../components/StatusBadge';
import { useAsync } from '../hooks/useAsync';
import { enumLabel, formatDateTime } from '../utils/format';

const FREQUENCIES: ScheduleFrequency[] = ['DAILY', 'WEEKLY', 'MONTHLY'];
const FORMATS: ScheduleFormat[] = ['PDF', 'XLSX', 'CSV'];

const PERIOD_HINT: Record<ScheduleFrequency, string> = {
  DAILY: 'Generated at 01:00 every day for the previous business day.',
  WEEKLY: 'Generated at 01:00 every Monday for the previous Monday–Sunday week.',
  MONTHLY: 'Generated at 01:00 on the 1st for the previous calendar month.',
};

type Scope = 'site' | 'region' | 'national';

function ScheduleForm({ schedule, onClose, onSaved }: { schedule: ReportSchedule | null; onClose: () => void; onSaved: () => void }) {
  const { me } = useAuth();
  const regions = useAsync(() => orgApi.listRegions(), []);
  const [f, setF] = useState<ReportScheduleInput>(schedule
    ? { name: schedule.name, frequency: schedule.frequency, groupBy: schedule.groupBy, format: schedule.format,
        siteId: schedule.siteId, regionId: schedule.regionId, active: schedule.active }
    : { name: '', frequency: 'MONTHLY', groupBy: 'SERVICE', format: 'PDF', siteId: me?.sites[0]?.id, active: true });
  const scope: Scope = f.siteId ? 'site' : f.regionId ? 'region' : 'national';
  const set = <K extends keyof ReportScheduleInput>(k: K) => (v: ReportScheduleInput[K]) => setF((p) => ({ ...p, [k]: v }));

  const setScope = (s: Scope) =>
    setF((p) => ({
      ...p,
      siteId: s === 'site' ? (p.siteId ?? me?.sites[0]?.id) : undefined,
      regionId: s === 'region' ? (p.regionId ?? regions.data?.[0]?.id) : undefined,
    }));

  return (
    <FormModal title={schedule ? `Edit “${schedule.name}”` : 'New scheduled report'} onClose={onClose}
      validate={() => (!f.name.trim() ? 'A name is required.' : scope === 'site' && !f.siteId ? 'Choose a site.' : null)}
      onSubmit={async () => {
        if (schedule) await reportSchedulesApi.update(schedule.id, f);
        else await reportSchedulesApi.create(f);
        onSaved();
      }}>
      {(fe) => (
        <>
          <TextField label="Name" required value={f.name} onChange={set('name')} error={fe('name')} maxLength={120} />
          <div className="row">
            <SelectField className="col-md-4 mb-3" label="Frequency" value={f.frequency}
              onChange={(v) => set('frequency')(v as ScheduleFrequency)} options={enumOptions(FREQUENCIES)}
              hint={PERIOD_HINT[f.frequency]} />
            <SelectField className="col-md-4 mb-3" label="Group by" value={f.groupBy}
              onChange={(v) => set('groupBy')(v as GroupBy)} options={enumOptions(GROUP_BYS)} />
            <SelectField className="col-md-4 mb-3" label="Format" value={f.format}
              onChange={(v) => set('format')(v as ScheduleFormat)} options={enumOptions(FORMATS)} />
          </div>
          <div className="row">
            <SelectField className="col-md-4 mb-3" label="Scope" value={scope} onChange={(v) => setScope(v as Scope)}
              options={[{ value: 'site', label: 'One site' }, { value: 'region', label: 'One region' }, { value: 'national', label: 'All sites (national)' }]}
              hint="Region and national scopes need matching report-export rights." />
            {scope === 'site' && (
              <SelectField className="col-md-8 mb-3" label="Site" value={f.siteId ?? ''} onChange={(v) => set('siteId')(v || undefined)}
                options={(me?.sites ?? []).map((s) => ({ value: s.id, label: `${s.code} — ${s.name}` }))} />
            )}
            {scope === 'region' && (
              <SelectField className="col-md-8 mb-3" label="Region" value={f.regionId ?? ''} onChange={(v) => set('regionId')(v || undefined)}
                options={(regions.data ?? []).map((r) => ({ value: r.id, label: r.name }))} />
            )}
          </div>
          <CheckField label="Active (generate automatically)" checked={f.active} onChange={set('active')} />
        </>
      )}
    </FormModal>
  );
}

function RunsModal({ schedule, onClose }: { schedule: ReportSchedule; onClose: () => void }) {
  const { can } = useAuth();
  const runs = useAsync(() => reportSchedulesApi.runs(schedule.id), [schedule.id]);
  return (
    <Modal title={`Generated files — ${schedule.name}`} size="lg" onClose={onClose}>
      <ErrorAlert error={runs.error} />
      <DataTable caption="Report runs" loading={runs.loading} rows={runs.data} rowKey={(r) => r.id}
        emptyText="No files generated yet. Use “Run now” or wait for the next scheduled run."
        columns={[
          { key: 'period', header: 'Period', render: (r) => (r.periodFrom === r.periodTo ? r.periodFrom : `${r.periodFrom} → ${r.periodTo}`) },
          { key: 'trigger', header: 'Trigger', render: (r) => enumLabel(r.trigger) },
          { key: 'status', header: 'Status', render: (r) => (
            <>
              <StatusBadge status={r.status} />
              {r.error && <div className="small text-danger">{r.error}</div>}
            </>
          ) },
          { key: 'generatedAt', header: 'Generated', render: (r) => formatDateTime(r.generatedAt) },
          { key: 'size', header: 'Size', className: 'text-end', render: (r) => (r.sizeBytes != null ? `${Math.max(1, Math.round(r.sizeBytes / 1024))} KB` : '—') },
          { key: 'file', header: <span className="visually-hidden">Download</span>, className: 'text-end',
            render: (r) => r.status === 'SUCCESS' && (r.fileName
              ? can('REPORT_EXPORT') && (
                <DownloadButton label="Download" className="btn btn-sm btn-outline-primary"
                  download={() => reportSchedulesApi.download(r, schedule)} />
              )
              : <span className="small text-body-secondary">Past retention</span>) },
        ]} />
    </Modal>
  );
}

/** Summary reports generated automatically every day, week or month, kept for download. */
export function ScheduledReportsPage() {
  const { can } = useAuth();
  const list = useAsync(() => reportSchedulesApi.list(), []);
  const [edit, setEdit] = useState<ReportSchedule | 'new' | null>(null);
  const [runsOf, setRunsOf] = useState<ReportSchedule | null>(null);
  const [running, setRunning] = useState<string | null>(null);
  const [error, setError] = useState<ApiErrorBody | null>(null);
  const canEdit = can('REPORT_EXPORT');

  const runNow = async (s: ReportSchedule) => {
    setRunning(s.id);
    setError(null);
    try {
      await reportSchedulesApi.runNow(s.id);
      await list.reload();
      setRunsOf(s);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setRunning(null);
    }
  };

  return (
    <>
      <PageHeader title="Scheduled Reports"
        subtitle="Summary reports generated automatically for each completed day, week or month"
        actions={canEdit && <button type="button" className="btn btn-primary" onClick={() => setEdit('new')}>New schedule</button>} />
      <ErrorAlert error={error ?? list.error} onDismiss={error ? () => setError(null) : undefined} />
      <DataTable caption="Report schedules" loading={list.loading} rows={list.data} rowKey={(s) => s.id}
        emptyText="No scheduled reports yet."
        columns={[
          { key: 'name', header: 'Name' },
          { key: 'frequency', header: 'Frequency', render: (s) => enumLabel(s.frequency) },
          { key: 'groupBy', header: 'Grouped by', render: (s) => enumLabel(s.groupBy) },
          { key: 'format', header: 'Format' },
          { key: 'scope', header: 'Scope', render: (s) => s.siteName ?? s.regionName ?? 'National' },
          { key: 'nextRunAt', header: 'Next run', render: (s) => (s.active ? formatDateTime(s.nextRunAt) : 'Paused') },
          { key: 'last', header: 'Last run', render: (s) => (s.lastRunStatus ? <StatusBadge status={s.lastRunStatus} /> : '—') },
          { key: 'actions', header: <span className="visually-hidden">Actions</span>, className: 'text-end text-nowrap',
            render: (s) => (
              <div className="btn-group btn-group-sm">
                <button type="button" className="btn btn-outline-secondary" onClick={() => setRunsOf(s)}>Files</button>
                {canEdit && (
                  <>
                    <button type="button" className="btn btn-outline-primary" disabled={running === s.id} onClick={() => void runNow(s)}>
                      {running === s.id ? 'Running…' : 'Run now'}
                    </button>
                    <button type="button" className="btn btn-outline-primary" onClick={() => setEdit(s)}>Edit</button>
                  </>
                )}
              </div>
            ) },
        ]} />
      <p className="small text-body-secondary mt-2">
        Files are kept on the server for the configured retention period. Email delivery is not available yet.
      </p>
      {edit && <ScheduleForm schedule={edit === 'new' ? null : edit} onClose={() => setEdit(null)} onSaved={() => void list.reload()} />}
      {runsOf && <RunsModal schedule={runsOf} onClose={() => setRunsOf(null)} />}
    </>
  );
}
