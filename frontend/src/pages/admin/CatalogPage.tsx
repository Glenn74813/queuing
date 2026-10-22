import { useState } from 'react';
import { catalogApi } from '../../api/catalog';
import { CLASSIFICATIONS, CLIENT_TYPES, SERVICE_TYPES } from '../../api/enums';
import type { Classification, ClientType, ServiceItem, ServiceItemInput, ServiceType } from '../../api/types';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { CheckField, SelectField, TextAreaField, TextField, enumOptions } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { Modal } from '../../components/Modal';
import { PageHeader } from '../../components/PageHeader';
import { ActiveBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';
import { enumLabel, formatDateTime } from '../../utils/format';

const EMPTY: ServiceItemInput = {
  code: '', name: '', description: '', classification: 'SIMPLE', clientType: 'G2C', serviceType: 'EXTERNAL',
  multiStage: false, defaultPrefix: '', defaultDurationMinutes: 10, requiredDocuments: '', eligibilityNotes: '',
  active: true, sortOrder: 0, effectiveFrom: '', effectiveTo: '',
};

function toInput(s: ServiceItem): ServiceItemInput {
  const { id: _id, version: _v, ...rest } = s;
  void _id;
  void _v;
  return { ...EMPTY, ...rest };
}

/** Blank optional strings are sent as undefined rather than "". */
function clean(f: ServiceItemInput): ServiceItemInput {
  return {
    ...f,
    description: f.description || undefined,
    requiredDocuments: f.requiredDocuments || undefined,
    eligibilityNotes: f.eligibilityNotes || undefined,
    effectiveFrom: f.effectiveFrom || undefined,
    effectiveTo: f.effectiveTo || undefined,
  };
}

function ServiceItemForm({ item, onClose, onSaved }: { item: ServiceItem | null; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState<ServiceItemInput>(item ? toInput(item) : EMPTY);
  const set = <K extends keyof ServiceItemInput>(k: K) => (v: ServiceItemInput[K]) => setF((p) => ({ ...p, [k]: v }));
  return (
    <FormModal title={item ? `Edit ${item.code} (v${item.version})` : 'New catalog service'} size="lg" onClose={onClose}
      validate={() => {
        if (!f.code || !f.name || !f.defaultPrefix) return 'Code, name and default prefix are required.';
        if (f.effectiveFrom && f.effectiveTo && f.effectiveTo <= f.effectiveFrom) return '"Effective to" must be after "Effective from".';
        return null;
      }}
      onSubmit={async () => {
        if (item) await catalogApi.updateServiceItem(item.id, clean(f));
        else await catalogApi.createServiceItem(clean(f));
        onSaved();
      }}>
      {(fe) => (
        <>
          <div className="row">
            <TextField className="col-md-3 mb-3" label="Code" required value={f.code} onChange={set('code')} error={fe('code')} />
            <TextField className="col-md-9 mb-3" label="Name" required value={f.name} onChange={set('name')} error={fe('name')} />
          </div>
          <TextAreaField label="Description" value={f.description ?? ''} onChange={set('description')} error={fe('description')} rows={2} />
          <div className="row">
            <SelectField className="col-md-4 mb-3" label="Classification" value={f.classification}
              onChange={(v) => set('classification')(v as Classification)} options={enumOptions(CLASSIFICATIONS)} />
            <SelectField className="col-md-4 mb-3" label="Client type" value={f.clientType}
              onChange={(v) => set('clientType')(v as ClientType)} options={enumOptions(CLIENT_TYPES)} />
            <SelectField className="col-md-4 mb-3" label="Service type" value={f.serviceType}
              onChange={(v) => set('serviceType')(v as ServiceType)} options={enumOptions(SERVICE_TYPES)} />
          </div>
          <div className="row">
            <TextField className="col-md-3 mb-3" label="Default prefix" required maxLength={3} value={f.defaultPrefix}
              onChange={(v) => set('defaultPrefix')(v.toUpperCase())} error={fe('defaultPrefix')} />
            <TextField className="col-md-3 mb-3" label="Default duration (min)" type="number" min={1} value={f.defaultDurationMinutes}
              onChange={(v) => set('defaultDurationMinutes')(Number(v))} error={fe('defaultDurationMinutes')} />
            <TextField className="col-md-3 mb-3" label="Sort order" type="number" value={f.sortOrder}
              onChange={(v) => set('sortOrder')(Number(v))} error={fe('sortOrder')} />
          </div>
          <div className="row">
            <TextField className="col-md-4 mb-3" label="Effective from" type="date" value={f.effectiveFrom ?? ''}
              onChange={set('effectiveFrom')} error={fe('effectiveFrom')} hint="First day offered. Blank = already in effect." />
            <TextField className="col-md-4 mb-3" label="Effective to" type="date" value={f.effectiveTo ?? ''}
              onChange={set('effectiveTo')} error={fe('effectiveTo')} hint="First day no longer offered. Blank = no end." />
          </div>
          <TextAreaField label="Required documents" value={f.requiredDocuments ?? ''} onChange={set('requiredDocuments')} rows={2} />
          <TextAreaField label="Eligibility notes" value={f.eligibilityNotes ?? ''} onChange={set('eligibilityNotes')} rows={2} />
          <div className="d-flex gap-4">
            <CheckField label="Multi-stage" checked={f.multiStage} onChange={set('multiStage')} />
            <CheckField label="Active" checked={f.active} onChange={set('active')} />
          </div>
        </>
      )}
    </FormModal>
  );
}

/** Every saved version of a catalog item, newest first, with its full snapshot. */
function HistoryModal({ item, onClose }: { item: ServiceItem; onClose: () => void }) {
  const history = useAsync(() => catalogApi.serviceItemHistory(item.id), [item.id]);
  return (
    <Modal title={`Version history — ${item.code}`} size="lg" onClose={onClose}>
      <ErrorAlert error={history.error} />
      <DataTable caption="Catalog item versions" loading={history.loading} rows={history.data} rowKey={(h) => String(h.version)}
        columns={[
          { key: 'version', header: 'Ver.', className: 'text-end' },
          { key: 'changedAt', header: 'Saved', render: (h) => formatDateTime(h.changedAt) },
          { key: 'changedBy', header: 'By', render: (h) => h.changedBy ?? '—' },
          { key: 'snapshot', header: 'Snapshot', render: (h) => (
            <pre className="small mb-0 text-wrap" style={{ maxWidth: '32rem' }}>{JSON.stringify(JSON.parse(h.snapshot), null, 1)}</pre>
          ) },
        ]} />
    </Modal>
  );
}

function effectivePeriod(s: ServiceItem): string {
  if (!s.effectiveFrom && !s.effectiveTo) return 'Always';
  return `${s.effectiveFrom ?? '…'} → ${s.effectiveTo ? `before ${s.effectiveTo}` : '…'}`;
}

export function CatalogPage() {
  const items = useAsync(() => catalogApi.listServiceItems(), []);
  const [edit, setEdit] = useState<ServiceItem | 'new' | null>(null);
  const [historyOf, setHistoryOf] = useState<ServiceItem | null>(null);
  return (
    <>
      <PageHeader title="Service Catalog" subtitle="National catalog of services offered at sites"
        actions={<button type="button" className="btn btn-primary" onClick={() => setEdit('new')}>New service</button>} />
      <ErrorAlert error={items.error} />
      <DataTable caption="Service catalog" loading={items.loading} rowKey={(s) => s.id}
        rows={items.data?.slice().sort((a, b) => a.sortOrder - b.sortOrder)}
        columns={[
          { key: 'code', header: 'Code' },
          { key: 'name', header: 'Name' },
          { key: 'classification', header: 'Classification', render: (s) => enumLabel(s.classification) },
          { key: 'clientType', header: 'Client' },
          { key: 'serviceType', header: 'Type', render: (s) => enumLabel(s.serviceType) },
          { key: 'defaultPrefix', header: 'Prefix' },
          { key: 'multiStage', header: 'Multi-stage', render: (s) => (s.multiStage ? 'Yes' : 'No') },
          { key: 'effective', header: 'Effective', render: (s) => effectivePeriod(s) },
          { key: 'version', header: 'Ver.', className: 'text-end' },
          { key: 'active', header: 'Status', render: (s) => <ActiveBadge active={s.active} /> },
          { key: 'edit', header: <span className="visually-hidden">Actions</span>, className: 'text-end',
            render: (s) => (
              <div className="btn-group btn-group-sm">
                <button type="button" className="btn btn-outline-primary" onClick={() => setEdit(s)}>Edit</button>
                <button type="button" className="btn btn-outline-secondary" onClick={() => setHistoryOf(s)}>History</button>
              </div>
            ) },
        ]} />
      {historyOf && <HistoryModal item={historyOf} onClose={() => setHistoryOf(null)} />}
      {edit && <ServiceItemForm item={edit === 'new' ? null : edit} onClose={() => setEdit(null)} onSaved={() => void items.reload()} />}
    </>
  );
}
