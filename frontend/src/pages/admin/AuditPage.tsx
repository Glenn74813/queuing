import { useState, type FormEvent } from 'react';
import type { AuditParams } from '../../api/types';
import { auditApi } from '../../api/system';
import { DataTable } from '../../components/DataTable';
import { DownloadButton } from '../../components/DownloadButton';
import { ErrorAlert } from '../../components/ErrorAlert';
import { TextField } from '../../components/FormFields';
import { PageHeader } from '../../components/PageHeader';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime, todayIso } from '../../utils/format';

const PAGE_SIZE = 50;

export function AuditPage() {
  const [form, setForm] = useState({ from: todayIso(-7), to: todayIso(), actor: '', action: '' });
  const [params, setParams] = useState<AuditParams>({ ...form, page: 0, size: PAGE_SIZE });
  const audit = useAsync(() => auditApi.list(params), [params]);

  const apply = (e: FormEvent) => {
    e.preventDefault();
    setParams({ ...form, page: 0, size: PAGE_SIZE });
  };
  const set = (k: keyof typeof form) => (v: string) => setForm((p) => ({ ...p, [k]: v }));

  return (
    <>
      <PageHeader title="Audit Log"
        actions={<DownloadButton label="Export CSV" download={() => auditApi.export({ from: params.from, to: params.to, actor: params.actor, action: params.action })} />} />
      <form className="row g-2 align-items-end mb-3" onSubmit={apply} aria-label="Audit filters">
        {/* ASSUMPTION: from/to are dates (yyyy-MM-dd), like the report filters. */}
        <TextField className="col-sm-6 col-md-2" label="From" type="date" value={form.from} onChange={set('from')} />
        <TextField className="col-sm-6 col-md-2" label="To" type="date" value={form.to} onChange={set('to')} />
        <TextField className="col-sm-6 col-md-3" label="Actor (username)" value={form.actor} onChange={set('actor')} />
        <TextField className="col-sm-6 col-md-3" label="Action" value={form.action} onChange={set('action')} placeholder="e.g. TICKET_VOID" />
        <div className="col-md-2">
          <button type="submit" className="btn btn-primary w-100">Apply</button>
        </div>
      </form>
      <ErrorAlert error={audit.error} />
      <DataTable caption="Audit entries" loading={audit.loading} rows={audit.data?.content} rowKey={(a) => a.id}
        paging={audit.data && { page: audit.data.page, totalPages: audit.data.totalPages, totalElements: audit.data.totalElements,
          onPageChange: (p) => setParams((prev) => ({ ...prev, page: p })) }}
        columns={[
          { key: 'at', header: 'When', render: (a) => formatDateTime(a.at) },
          { key: 'actorUsername', header: 'Actor', render: (a) => a.actorUsername ?? 'system' },
          { key: 'action', header: 'Action', render: (a) => <code>{a.action}</code> },
          { key: 'entity', header: 'Entity', render: (a) => (a.entityType ? `${a.entityType} ${a.entityId ?? ''}` : '—') },
          { key: 'details', header: 'Details', render: (a) => <span className="small text-break">{a.details ?? '—'}</span> },
          { key: 'ipAddress', header: 'IP', render: (a) => a.ipAddress ?? '—' },
        ]} />
    </>
  );
}
