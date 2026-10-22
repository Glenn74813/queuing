import { useState } from 'react';
import { catalogApi } from '../../api/catalog';
import { slaApi } from '../../api/sla';
import type { SlaPolicy, SlaPolicyInput } from '../../api/types';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { SelectField, TextField } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { PageHeader } from '../../components/PageHeader';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime } from '../../utils/format';

type OverrideRow = { serviceItemId: string; waiting: string; processing: string };

const toNum = (v: string) => (v.trim() === '' ? undefined : Number(v));

function SlaForm({ policy, onClose, onSaved }: { policy: SlaPolicy; onClose: () => void; onSaved: () => void }) {
  const catalog = useAsync(() => catalogApi.listServiceItems(), []);
  const [waiting, setWaiting] = useState(String(policy.waitingTargetMinutes));
  const [processing, setProcessing] = useState(String(policy.processingTargetMinutes));
  const [compliance, setCompliance] = useState(String(policy.complianceTargetPercent));
  // An inherited row starts from the national values but without the national overrides.
  const [overrides, setOverrides] = useState<OverrideRow[]>(policy.inherited ? [] : policy.serviceTargets.map((t) => ({
    serviceItemId: t.serviceItemId,
    waiting: t.waitingTargetMinutes?.toString() ?? '',
    processing: t.processingTargetMinutes?.toString() ?? '',
  })));
  const setRow = (i: number, patch: Partial<OverrideRow>) =>
    setOverrides((rows) => rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const isNational = !policy.regionId;

  return (
    <FormModal title={`SLA — ${policy.regionName}`} size="lg" onClose={onClose}
      validate={() => {
        if (![waiting, processing, compliance].every((v) => Number(v) > 0)) return 'All three targets are required and must be above zero.';
        if (Number(compliance) > 100) return 'Compliance target cannot exceed 100%.';
        if (overrides.some((o) => !o.serviceItemId)) return 'Choose a service for every override, or remove the empty row.';
        if (overrides.some((o) => !o.waiting.trim() && !o.processing.trim())) return 'Each override needs a waiting or a processing target.';
        if (new Set(overrides.map((o) => o.serviceItemId)).size !== overrides.length) return 'Each service may have only one override.';
        return null;
      }}
      onSubmit={async () => {
        const body: SlaPolicyInput = {
          waitingTargetMinutes: Number(waiting),
          processingTargetMinutes: Number(processing),
          complianceTargetPercent: Number(compliance),
          serviceTargets: overrides.map((o) => ({
            serviceItemId: o.serviceItemId,
            waitingTargetMinutes: toNum(o.waiting),
            processingTargetMinutes: toNum(o.processing),
          })),
        };
        if (isNational) await slaApi.saveNational(body);
        else await slaApi.saveRegion(policy.regionId as string, body);
        onSaved();
      }}>
      {(fe) => (
        <>
          {policy.inherited && (
            <div className="alert alert-info small">
              This region currently uses the national default. Saving creates its own SLA.
            </div>
          )}
          <div className="row">
            <TextField className="col-md-4 mb-3" label="Waiting target (min)" type="number" min={1} max={1440} required
              value={waiting} onChange={setWaiting} error={fe('waitingTargetMinutes')}
              hint="Longest acceptable wait before the client is called." />
            <TextField className="col-md-4 mb-3" label="Processing target (min)" type="number" min={1} max={1440} required
              value={processing} onChange={setProcessing} error={fe('processingTargetMinutes')}
              hint="Longest acceptable time at the counter." />
            <TextField className="col-md-4 mb-3" label="Compliance target (%)" type="number" min={1} max={100} step="0.5" required
              value={compliance} onChange={setCompliance} error={fe('complianceTargetPercent')}
              hint="Share of transactions within target needed to meet the SLA." />
          </div>

          <h3 className="h6 mt-2">Service overrides</h3>
          <p className="small text-body-secondary">Services that need different targets, e.g. accreditation applications.</p>
          {overrides.map((o, i) => (
            <div key={i} className="row g-2 align-items-end mb-2">
              <SelectField className="col-md-6" label="Service" value={o.serviceItemId} placeholder="Select…"
                onChange={(v) => setRow(i, { serviceItemId: v })}
                options={(catalog.data ?? []).map((s) => ({ value: s.id, label: `${s.code} — ${s.name}` }))} />
              <TextField className="col-md-2" label="Waiting (min)" type="number" min={1} value={o.waiting}
                onChange={(v) => setRow(i, { waiting: v })} />
              <TextField className="col-md-2" label="Processing (min)" type="number" min={1} value={o.processing}
                onChange={(v) => setRow(i, { processing: v })} />
              <div className="col-md-2">
                <button type="button" className="btn btn-outline-danger w-100"
                  onClick={() => setOverrides((rows) => rows.filter((_, j) => j !== i))}>
                  Remove
                </button>
              </div>
            </div>
          ))}
          <button type="button" className="btn btn-sm btn-outline-primary"
            onClick={() => setOverrides((rows) => [...rows, { serviceItemId: '', waiting: '', processing: '' }])}>
            Add service override
          </button>
        </>
      )}
    </FormModal>
  );
}

/** Service level targets per region; agent performance in Reports is measured against them. */
export function SlaPage() {
  const list = useAsync(() => slaApi.list(), []);
  const [edit, setEdit] = useState<SlaPolicy | null>(null);
  const [revert, setRevert] = useState<SlaPolicy | null>(null);

  return (
    <>
      <PageHeader title="SLA Targets"
        subtitle="Service level targets per region. Reports › Agent performance measures each agent against the SLA of their site's region." />
      <ErrorAlert error={list.error} />
      <DataTable caption="SLA targets by region" loading={list.loading} rows={list.data}
        rowKey={(p) => p.regionId ?? 'national'}
        columns={[
          { key: 'regionName', header: 'Region', render: (p) => (p.regionId ? p.regionName : <strong>{p.regionName}</strong>) },
          { key: 'source', header: 'Source', render: (p) => (!p.regionId ? 'Applies to regions without their own SLA'
            : p.inherited ? <span className="text-body-secondary">National default</span> : 'Own SLA') },
          { key: 'waitingTargetMinutes', header: 'Waiting (min)', className: 'text-end' },
          { key: 'processingTargetMinutes', header: 'Processing (min)', className: 'text-end' },
          { key: 'complianceTargetPercent', header: 'Compliance', className: 'text-end', render: (p) => `${p.complianceTargetPercent}%` },
          { key: 'overrides', header: 'Service overrides', render: (p) => (p.inherited ? '—' : p.serviceTargets.length === 0 ? 'None'
            : p.serviceTargets.map((t) => (
              <div key={t.serviceItemId} className="small">
                {t.serviceCode}: wait {t.waitingTargetMinutes ?? '—'} / process {t.processingTargetMinutes ?? '—'} min
              </div>
            ))) },
          { key: 'updated', header: 'Last change', render: (p) => (p.inherited ? '—' : `${formatDateTime(p.updatedAt)} by ${p.updatedBy ?? '—'}`) },
          { key: 'actions', header: <span className="visually-hidden">Actions</span>, className: 'text-end text-nowrap',
            render: (p) => (
              <div className="btn-group btn-group-sm">
                <button type="button" className="btn btn-outline-primary" onClick={() => setEdit(p)}>
                  {p.inherited ? 'Set own SLA' : 'Edit'}
                </button>
                {p.regionId && !p.inherited && (
                  <button type="button" className="btn btn-outline-secondary" onClick={() => setRevert(p)}>Use national default</button>
                )}
              </div>
            ) },
        ]} />
      {edit && <SlaForm policy={edit} onClose={() => setEdit(null)} onSaved={() => void list.reload()} />}
      {revert && (
        <ConfirmDialog title={`Use the national default for ${revert.regionName}?`}
          message="The region's own targets and service overrides are removed; agents there are then measured against the national default."
          confirmLabel="Use national default"
          onConfirm={() => slaApi.removeRegion(revert.regionId as string).then(() => list.reload())}
          onClose={() => setRevert(null)} />
      )}
    </>
  );
}
