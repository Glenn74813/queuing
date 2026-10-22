import { useState } from 'react';
import { catalogApi } from '../../api/catalog';
import type { ServiceItem, SiteService, SiteServiceInput } from '../../api/types';
import { useSite } from '../../auth/SiteContext';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { CheckField, SelectField, TextField } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { PageHeader } from '../../components/PageHeader';
import { NoSiteNotice } from '../../components/SiteSelector';
import { ActiveBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';

function SiteServiceForm({ siteId, svc, catalog, services, onClose, onSaved }: {
  siteId: string; svc: SiteService | null; catalog: ServiceItem[]; services: SiteService[]; onClose: () => void; onSaved: () => void;
}) {
  const [f, setF] = useState<SiteServiceInput>({
    serviceItemId: svc?.serviceItemId ?? '',
    prefix: svc?.prefix ?? '',
    active: svc?.active ?? true,
    sortOrder: svc?.sortOrder ?? 0,
    estimatedDurationMinutes: svc?.estimatedDurationMinutes ?? 10,
    openTime: svc?.openTime ?? '',
    closeTime: svc?.closeTime ?? '',
    nextSiteServiceId: svc?.nextSiteServiceId ?? '',
  });
  const set = <K extends keyof SiteServiceInput>(k: K) => (v: SiteServiceInput[K]) => setF((p) => ({ ...p, [k]: v }));
  const pickItem = (id: string) => {
    const item = catalog.find((c) => c.id === id);
    // Pre-fill defaults from the catalog item when adding a new site service.
    setF((p) => ({
      ...p,
      serviceItemId: id,
      prefix: p.prefix || item?.defaultPrefix || '',
      estimatedDurationMinutes: svc ? p.estimatedDurationMinutes : item?.defaultDurationMinutes ?? p.estimatedDurationMinutes,
    }));
  };
  return (
    <FormModal title={svc ? `Edit ${svc.serviceName}` : 'Add service to site'} onClose={onClose}
      validate={() => (!f.serviceItemId || !f.prefix ? 'Service and prefix are required.' : null)}
      onSubmit={async () => {
        const body = { ...f, openTime: f.openTime || undefined, closeTime: f.closeTime || undefined,
          nextSiteServiceId: f.nextSiteServiceId || undefined };
        if (svc) await catalogApi.updateSiteService(siteId, svc.id, body);
        else await catalogApi.createSiteService(siteId, body);
        onSaved();
      }}>
      {(fe) => (
        <>
          <SelectField label="Catalog service" required value={f.serviceItemId} onChange={pickItem} disabled={!!svc}
            placeholder="Select…" error={fe('serviceItemId')}
            options={catalog.filter((c) => c.active || c.id === f.serviceItemId).map((c) => ({ value: c.id, label: `${c.code} — ${c.name}` }))} />
          <div className="row">
            <TextField className="col-md-4 mb-3" label="Ticket prefix" required maxLength={3} value={f.prefix}
              onChange={(v) => set('prefix')(v.toUpperCase())} error={fe('prefix')} />
            <TextField className="col-md-4 mb-3" label="Est. duration (min)" type="number" min={1} value={f.estimatedDurationMinutes}
              onChange={(v) => set('estimatedDurationMinutes')(Number(v))} error={fe('estimatedDurationMinutes')} />
            <TextField className="col-md-4 mb-3" label="Sort order" type="number" value={f.sortOrder}
              onChange={(v) => set('sortOrder')(Number(v))} error={fe('sortOrder')} />
          </div>
          <div className="row">
            <TextField className="col-md-6 mb-3" label="Opens" type="time" value={f.openTime ?? ''} onChange={set('openTime')} error={fe('openTime')} />
            <TextField className="col-md-6 mb-3" label="Closes" type="time" value={f.closeTime ?? ''} onChange={set('closeTime')} error={fe('closeTime')} />
          </div>
          <SelectField label="Next stage (multi-stage flow)" value={f.nextSiteServiceId ?? ''} placeholder="None — last stage"
            onChange={(v) => set('nextSiteServiceId')(v)} error={fe('nextSiteServiceId')}
            hint="Agents get a one-click “Next stage” button that moves the client to this service's queue."
            options={services.filter((s) => s.id !== svc?.id).map((s) => ({ value: s.id, label: `${s.prefix} — ${s.serviceName}` }))} />
          <CheckField label="Active" checked={f.active} onChange={set('active')} />
        </>
      )}
    </FormModal>
  );
}

export function SiteServicesPage() {
  const { site, siteId } = useSite();
  const services = useAsync(() => catalogApi.listSiteServices(siteId as string), [siteId], !!siteId);
  const catalog = useAsync(() => catalogApi.listServiceItems(), []);
  const [edit, setEdit] = useState<SiteService | 'new' | null>(null);
  if (!siteId) return <NoSiteNotice />;
  return (
    <>
      <PageHeader title="Site Services" subtitle={site?.name}
        actions={<button type="button" className="btn btn-primary" disabled={!catalog.data} onClick={() => setEdit('new')}>Add service</button>} />
      <ErrorAlert error={services.error ?? catalog.error} />
      <DataTable caption="Services offered at this site" loading={services.loading} rowKey={(s) => s.id}
        rows={services.data?.slice().sort((a, b) => a.sortOrder - b.sortOrder)}
        columns={[
          { key: 'serviceCode', header: 'Code' },
          { key: 'serviceName', header: 'Service' },
          { key: 'prefix', header: 'Prefix' },
          { key: 'estimatedDurationMinutes', header: 'Est. min', className: 'text-end' },
          { key: 'hours', header: 'Hours', render: (s) => (s.openTime || s.closeTime ? `${s.openTime ?? '—'}–${s.closeTime ?? '—'}` : 'All day') },
          { key: 'next', header: 'Next stage', render: (s) => services.data?.find((n) => n.id === s.nextSiteServiceId)?.serviceName ?? '—' },
          { key: 'sortOrder', header: 'Order', className: 'text-end' },
          { key: 'active', header: 'Status', render: (s) => <ActiveBadge active={s.active} /> },
          { key: 'edit', header: <span className="visually-hidden">Actions</span>, className: 'text-end',
            render: (s) => <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setEdit(s)}>Edit</button> },
        ]} />
      {edit && (
        <SiteServiceForm siteId={siteId} svc={edit === 'new' ? null : edit} catalog={catalog.data ?? []} services={services.data ?? []}
          onClose={() => setEdit(null)} onSaved={() => void services.reload()} />
      )}
    </>
  );
}
