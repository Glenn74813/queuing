import { useState } from 'react';
import { catalogApi } from '../../api/catalog';
import { countersApi } from '../../api/counters';
import type { Counter, CounterHours, CounterInput, SiteService } from '../../api/types';
import { useSite } from '../../auth/SiteContext';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { CheckField, TextField } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { PageHeader } from '../../components/PageHeader';
import { NoSiteNotice } from '../../components/SiteSelector';
import { ActiveBadge, StatusBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';
import { DAY_NAMES, formatHours, hoursError } from '../../utils/counterHours';

/** Weekly opening windows; several per day express breaks (e.g. lunch). */
function HoursEditor({ hours, onChange }: { hours: CounterHours[]; onChange: (h: CounterHours[]) => void }) {
  const forDay = (d: number) => hours.map((h, i) => ({ h, i })).filter(({ h }) => h.dayOfWeek === d);
  const set = (i: number, patch: Partial<CounterHours>) => onChange(hours.map((h, j) => (j === i ? { ...h, ...patch } : h)));
  const add = (d: number) => {
    const existing = forDay(d);
    const last = existing[existing.length - 1]?.h;
    onChange([...hours, last ? { dayOfWeek: d, openTime: last.closeTime.slice(0, 5), closeTime: '17:00' } : { dayOfWeek: d, openTime: '08:00', closeTime: '17:00' }]);
  };
  const copyMonday = () => onChange([
    ...hours.filter((h) => h.dayOfWeek === 1 || h.dayOfWeek > 5),
    ...[2, 3, 4, 5].flatMap((d) => hours.filter((h) => h.dayOfWeek === 1).map((h) => ({ ...h, dayOfWeek: d }))),
  ]);
  return (
    <div>
      <table className="table table-sm align-middle mb-2">
        <caption className="visually-hidden">Opening hours by weekday</caption>
        <tbody>
          {DAY_NAMES.map((name, idx) => {
            const d = idx + 1;
            return (
              <tr key={d}>
                <th scope="row" className="fw-normal" style={{ width: '3.5rem' }}>{name}</th>
                <td>
                  {forDay(d).length === 0 && <span className="text-body-secondary small me-2">Closed</span>}
                  {forDay(d).map(({ h, i }) => (
                    <span key={i} className="d-inline-flex align-items-center gap-1 me-3 mb-1">
                      <input type="time" className="form-control form-control-sm" style={{ width: '7.5rem' }}
                        aria-label={`${name} opens`} value={h.openTime.slice(0, 5)} onChange={(e) => set(i, { openTime: e.target.value })} />
                      <span aria-hidden="true">–</span>
                      <input type="time" className="form-control form-control-sm" style={{ width: '7.5rem' }}
                        aria-label={`${name} closes`} value={h.closeTime.slice(0, 5)} onChange={(e) => set(i, { closeTime: e.target.value })} />
                      <button type="button" className="btn btn-sm btn-link text-danger px-1" aria-label={`Remove ${name} window`}
                        onClick={() => onChange(hours.filter((_, j) => j !== i))}>✕</button>
                    </span>
                  ))}
                  <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => add(d)}>
                    {forDay(d).length === 0 ? 'Open' : 'Add window'}
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <button type="button" className="btn btn-sm btn-outline-primary" disabled={!hours.some((h) => h.dayOfWeek === 1)}
        onClick={copyMonday}>Copy Monday to Tuesday–Friday</button>
    </div>
  );
}

function CounterForm({ siteId, counter, services, onClose, onSaved }: {
  siteId: string; counter: Counter | null; services: SiteService[]; onClose: () => void; onSaved: () => void;
}) {
  const [f, setF] = useState<CounterInput>({
    code: counter?.code ?? '', name: counter?.name ?? '', active: counter?.active ?? true, siteServiceIds: counter?.siteServiceIds ?? [],
    hours: counter?.hours ?? [],
  });
  const [scheduled, setScheduled] = useState((counter?.hours.length ?? 0) > 0);
  const hours = f.hours ?? [];
  const toggle = (id: string, on: boolean) =>
    setF((p) => ({ ...p, siteServiceIds: on ? [...p.siteServiceIds, id] : p.siteServiceIds.filter((x) => x !== id) }));
  return (
    <FormModal title={counter ? `Edit ${counter.name}` : 'New counter'} onClose={onClose}
      size="lg"
      validate={() => (!f.code || !f.name ? 'Code and name are required.'
        : f.siteServiceIds.length === 0 ? 'Select at least one eligible service.'
          : scheduled && hours.length === 0 ? 'Add at least one opening window, or untick "Set opening hours".'
            : scheduled ? hoursError(hours) : null)}
      onSubmit={async () => {
        const body = { ...f, hours: scheduled ? hours : [] };
        if (counter) await countersApi.update(siteId, counter.id, body);
        else await countersApi.create(siteId, body);
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
          <fieldset className="mb-3">
            <legend className="form-label fs-6">Opening hours</legend>
            <CheckField label="Set opening hours" checked={scheduled} onChange={setScheduled}
              hint="Without hours the counter is always available. With hours, kiosks offer a service only while an eligible counter is open or has an agent signed in." />
            {scheduled && <HoursEditor hours={hours} onChange={(h) => setF({ ...f, hours: h })} />}
            {fe('hours') && <div className="text-danger small">{fe('hours')}</div>}
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
          { key: 'hours', header: 'Opening hours', render: (c) => <span className="small">{formatHours(c.hours)}</span> },
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
