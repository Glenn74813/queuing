import { useState } from 'react';
import { catalogApi } from '../../api/catalog';
import { countersApi } from '../../api/counters';
import type { Counter, CounterInput, SiteService } from '../../api/types';
import { useSite } from '../../auth/SiteContext';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { CheckField, TextField } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { PageHeader } from '../../components/PageHeader';
import { NoSiteNotice } from '../../components/SiteSelector';
import { ActiveBadge, StatusBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';

function CounterForm({ siteId, counter, services, onClose, onSaved }: {
  siteId: string; counter: Counter | null; services: SiteService[]; onClose: () => void; onSaved: () => void;
}) {
  const [f, setF] = useState<CounterInput>({
    code: counter?.code ?? '', name: counter?.name ?? '', active: counter?.active ?? true, siteServiceIds: counter?.siteServiceIds ?? [],
  });
  const toggle = (id: string, on: boolean) =>
    setF((p) => ({ ...p, siteServiceIds: on ? [...p.siteServiceIds, id] : p.siteServiceIds.filter((x) => x !== id) }));
  return (
    <FormModal title={counter ? `Edit ${counter.name}` : 'New counter'} onClose={onClose}
      validate={() => (!f.code || !f.name ? 'Code and name are required.' : f.siteServiceIds.length === 0 ? 'Select at least one eligible service.' : null)}
      onSubmit={async () => {
        if (counter) await countersApi.update(siteId, counter.id, f);
        else await countersApi.create(siteId, f);
        onSaved();
      }}>
      {(fe) => (
        <>
          <div className="row">
            <TextField className="col-md-4 mb-3" label="Code" required value={f.code} onChange={(v) => setF({ ...f, code: v })} error={fe('code')} />
            <TextField className="col-md-8 mb-3" label="Name" required value={f.name} onChange={(v) => setF({ ...f, name: v })} error={fe('name')}
              hint='Announced on the display, e.g. "Counter 3"' />
          </div>
          <fieldset className="mb-3">
            <legend className="form-label fs-6">Eligible services</legend>
            {services.length === 0 && <p className="small text-body-secondary">No services configured for this site.</p>}
            {services.map((s) => (
              <CheckField key={s.id} className="mb-1" label={`${s.prefix} — ${s.serviceName}${s.active ? '' : ' (inactive)'}`}
                checked={f.siteServiceIds.includes(s.id)} onChange={(on) => toggle(s.id, on)} />
            ))}
            {fe('siteServiceIds') && <div className="text-danger small">{fe('siteServiceIds')}</div>}
          </fieldset>
          <CheckField label="Active" checked={f.active} onChange={(v) => setF({ ...f, active: v })} />
        </>
      )}
    </FormModal>
  );
}

export function CountersPage() {
  const { site, siteId } = useSite();
  const counters = useAsync(() => countersApi.list(siteId as string), [siteId], !!siteId);
  const services = useAsync(() => catalogApi.listSiteServices(siteId as string), [siteId], !!siteId);
  const [edit, setEdit] = useState<Counter | 'new' | null>(null);
  if (!siteId) return <NoSiteNotice />;
  const svcName = (id: string) => services.data?.find((s) => s.id === id)?.prefix ?? '?';
  return (
    <>
      <PageHeader title="Counters" subtitle={site?.name}
        actions={<button type="button" className="btn btn-primary" onClick={() => setEdit('new')}>New counter</button>} />
      <ErrorAlert error={counters.error ?? services.error} />
      <DataTable caption="Counters" loading={counters.loading} rows={counters.data} rowKey={(c) => c.id}
        columns={[
          { key: 'code', header: 'Code' },
          { key: 'name', header: 'Name' },
          { key: 'services', header: 'Services', render: (c) => c.siteServiceIds.map(svcName).join(', ') || '—' },
          { key: 'agent', header: 'Agent', render: (c) => (c.currentAgent ? <>{c.currentAgent.fullName} <StatusBadge status={c.agentStatus} /></> : '—') },
          { key: 'active', header: 'Status', render: (c) => <ActiveBadge active={c.active} /> },
          { key: 'edit', header: <span className="visually-hidden">Actions</span>, className: 'text-end',
            render: (c) => <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setEdit(c)}>Edit</button> },
        ]} />
      {edit && (
        <CounterForm siteId={siteId} counter={edit === 'new' ? null : edit} services={services.data ?? []}
          onClose={() => setEdit(null)} onSaved={() => void counters.reload()} />
      )}
    </>
  );
}
