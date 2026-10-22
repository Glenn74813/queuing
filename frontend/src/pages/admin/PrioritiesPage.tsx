import { useState } from 'react';
import { catalogApi } from '../../api/catalog';
import type { PriorityCategory, PriorityCategoryInput } from '../../api/types';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { CheckField, TextAreaField, TextField } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { PageHeader } from '../../components/PageHeader';
import { ActiveBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';

function PriorityForm({ item, onClose, onSaved }: { item: PriorityCategory | null; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState<PriorityCategoryInput>({
    code: item?.code ?? '', name: item?.name ?? '', description: item?.description ?? '', rank: item?.rank ?? 10, active: item?.active ?? true,
  });
  const set = <K extends keyof PriorityCategoryInput>(k: K) => (v: PriorityCategoryInput[K]) => setF((p) => ({ ...p, [k]: v }));
  return (
    <FormModal title={item ? `Edit ${item.code}` : 'New priority category'} onClose={onClose}
      validate={() => (!f.code || !f.name ? 'Code and name are required.' : null)}
      onSubmit={async () => {
        const body = { ...f, description: f.description || undefined };
        if (item) await catalogApi.updatePriority(item.id, body);
        else await catalogApi.createPriority(body);
        onSaved();
      }}>
      {(fe) => (
        <>
          <div className="row">
            <TextField className="col-md-4 mb-3" label="Code" required value={f.code} onChange={set('code')} error={fe('code')} />
            <TextField className="col-md-8 mb-3" label="Name" required value={f.name} onChange={set('name')} error={fe('name')}
              hint="Shown on the kiosk, e.g. Senior citizen" />
          </div>
          <TextAreaField label="Description" value={f.description ?? ''} onChange={set('description')} rows={2} />
          <TextField label="Rank" type="number" min={1} value={f.rank} onChange={(v) => set('rank')(Number(v))} error={fe('rank')}
            hint="Lower is served first; 100 is the regular lane." />
          <CheckField label="Active" checked={f.active} onChange={set('active')} />
        </>
      )}
    </FormModal>
  );
}

export function PrioritiesPage() {
  const items = useAsync(() => catalogApi.listPriorities(), []);
  const [edit, setEdit] = useState<PriorityCategory | 'new' | null>(null);
  return (
    <>
      <PageHeader title="Priority Categories"
        actions={<button type="button" className="btn btn-primary" onClick={() => setEdit('new')}>New category</button>} />
      <ErrorAlert error={items.error} />
      <DataTable caption="Priority categories" loading={items.loading} rowKey={(p) => p.id}
        rows={items.data?.slice().sort((a, b) => a.rank - b.rank)}
        columns={[
          { key: 'rank', header: 'Rank', className: 'text-end' },
          { key: 'code', header: 'Code' },
          { key: 'name', header: 'Name' },
          { key: 'description', header: 'Description', render: (p) => p.description ?? '—' },
          { key: 'active', header: 'Status', render: (p) => <ActiveBadge active={p.active} /> },
          { key: 'edit', header: <span className="visually-hidden">Actions</span>, className: 'text-end',
            render: (p) => <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setEdit(p)}>Edit</button> },
        ]} />
      {edit && <PriorityForm item={edit === 'new' ? null : edit} onClose={() => setEdit(null)} onSaved={() => void items.reload()} />}
    </>
  );
}
