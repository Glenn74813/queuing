import { useState } from 'react';
import { orgApi } from '../../api/org';
import type { Region, Site, SiteInput, SiteType } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { CheckField, SelectField, TextField, enumOptions } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { PageHeader } from '../../components/PageHeader';
import { ActiveBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';
import { enumLabel } from '../../utils/format';

const SITE_TYPES: SiteType[] = ['REGIONAL_OFFICE', 'LHIO', 'CENTRAL_OFFICE'];

function RegionForm({ region, onClose, onSaved }: { region: Region | null; onClose: () => void; onSaved: () => void }) {
  const [code, setCode] = useState(region?.code ?? '');
  const [name, setName] = useState(region?.name ?? '');
  const [active, setActive] = useState(region?.active ?? true);
  return (
    <FormModal
      title={region ? `Edit region ${region.code}` : 'New region'}
      validate={() => (!code.trim() || !name.trim() ? 'Code and name are required.' : null)}
      onSubmit={async () => {
        if (region) await orgApi.updateRegion(region.id, { code, name, active });
        else await orgApi.createRegion({ code, name });
        onSaved();
      }}
      onClose={onClose}
    >
      {(fe) => (
        <>
          <TextField label="Code" required value={code} onChange={setCode} error={fe('code')} maxLength={20} />
          <TextField label="Name" required value={name} onChange={setName} error={fe('name')} />
          {region && <CheckField label="Active" checked={active} onChange={setActive} />}
        </>
      )}
    </FormModal>
  );
}

function SiteForm({ site, regions, onClose, onSaved }: { site: Site | null; regions: Region[]; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState<SiteInput & { active: boolean }>({
    code: site?.code ?? '',
    name: site?.name ?? '',
    type: site?.type ?? 'LHIO',
    regionId: site?.regionId ?? '',
    area: site?.area ?? '',
    timezone: site?.timezone ?? 'Asia/Manila',
    active: site?.active ?? true,
  });
  const set = <K extends keyof typeof f>(k: K) => (v: (typeof f)[K]) => setF((p) => ({ ...p, [k]: v }));
  return (
    <FormModal
      title={site ? `Edit site ${site.code}` : 'New site'}
      validate={() => (!f.code || !f.name || !f.regionId || !f.timezone ? 'Code, name, region and time zone are required.' : null)}
      onSubmit={async () => {
        const body = { ...f, area: f.area || undefined };
        if (site) await orgApi.updateSite(site.id, body);
        else {
          // Create does not take `active` (new sites start active).
          const { code, name, type, regionId, area, timezone } = body;
          await orgApi.createSite({ code, name, type, regionId, area, timezone });
        }
        onSaved();
      }}
      onClose={onClose}
    >
      {(fe) => (
        <>
          <div className="row">
            <TextField className="col-md-4 mb-3" label="Code" required value={f.code} onChange={set('code')} error={fe('code')} />
            <TextField className="col-md-8 mb-3" label="Name" required value={f.name} onChange={set('name')} error={fe('name')} />
          </div>
          <div className="row">
            <SelectField className="col-md-6 mb-3" label="Type" required value={f.type}
              onChange={(v) => set('type')(v as SiteType)} options={enumOptions(SITE_TYPES)} error={fe('type')} />
            <SelectField className="col-md-6 mb-3" label="Region" required value={f.regionId} onChange={set('regionId')}
              placeholder="Select…" options={regions.map((r) => ({ value: r.id, label: `${r.code} — ${r.name}` }))} error={fe('regionId')} />
          </div>
          <div className="row">
            <TextField className="col-md-6 mb-3" label="Area" value={f.area ?? ''} onChange={set('area')} error={fe('area')} />
            <TextField className="col-md-6 mb-3" label="Time zone" required value={f.timezone} onChange={set('timezone')}
              error={fe('timezone')} hint="IANA zone, e.g. Asia/Manila" />
          </div>
          {site && <CheckField label="Active" checked={f.active} onChange={set('active')} />}
        </>
      )}
    </FormModal>
  );
}

export function OrgPage() {
  const { can, reloadMe } = useAuth();
  const regions = useAsync(() => orgApi.listRegions(), []);
  const [regionFilter, setRegionFilter] = useState('');
  const sites = useAsync(() => orgApi.listSites(regionFilter || undefined), [regionFilter]);
  const [editRegion, setEditRegion] = useState<Region | null | 'new'>(null);
  const [editSite, setEditSite] = useState<Site | null | 'new'>(null);
  const canRegion = can('REGION_MANAGE');
  const canSite = can('SITE_MANAGE');

  return (
    <>
      <PageHeader title="Regions & Sites" />
      <section className="mb-4" aria-labelledby="reg-h">
        <div className="d-flex justify-content-between align-items-center">
          <h2 id="reg-h" className="h5">Regions</h2>
          {canRegion && <button type="button" className="btn btn-sm btn-primary" onClick={() => setEditRegion('new')}>New region</button>}
        </div>
        <ErrorAlert error={regions.error} />
        <DataTable caption="Regions" loading={regions.loading} rows={regions.data} rowKey={(r) => r.id}
          columns={[
            { key: 'code', header: 'Code' },
            { key: 'name', header: 'Name' },
            { key: 'active', header: 'Status', render: (r) => <ActiveBadge active={r.active} /> },
            ...(canRegion ? [{ key: 'edit', header: <span className="visually-hidden">Actions</span>, className: 'text-end',
              render: (r: Region) => <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setEditRegion(r)}>Edit</button> }] : []),
          ]} />
      </section>

      <section aria-labelledby="site-h">
        <div className="d-flex flex-wrap justify-content-between align-items-end gap-2 mb-2">
          <h2 id="site-h" className="h5 mb-0">Sites</h2>
          <div className="d-flex gap-2 align-items-end">
            <SelectField className="mb-0" label="Filter by region" value={regionFilter} onChange={setRegionFilter}
              placeholder="All regions" options={(regions.data ?? []).map((r) => ({ value: r.id, label: r.name }))} />
            {canSite && <button type="button" className="btn btn-sm btn-primary" onClick={() => setEditSite('new')}>New site</button>}
          </div>
        </div>
        <ErrorAlert error={sites.error} />
        <DataTable caption="Sites" loading={sites.loading} rows={sites.data} rowKey={(s) => s.id}
          columns={[
            { key: 'code', header: 'Code' },
            { key: 'name', header: 'Name' },
            { key: 'type', header: 'Type', render: (s) => enumLabel(s.type) },
            { key: 'regionName', header: 'Region' },
            { key: 'area', header: 'Area', render: (s) => s.area ?? '—' },
            { key: 'timezone', header: 'Time zone' },
            { key: 'active', header: 'Status', render: (s) => <ActiveBadge active={s.active} /> },
            ...(canSite ? [{ key: 'edit', header: <span className="visually-hidden">Actions</span>, className: 'text-end',
              render: (s: Site) => <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setEditSite(s)}>Edit</button> }] : []),
          ]} />
      </section>

      {editRegion && (
        <RegionForm region={editRegion === 'new' ? null : editRegion} onClose={() => setEditRegion(null)} onSaved={() => { void regions.reload(); void reloadMe(); }} />
      )}
      {editSite && (
        <SiteForm site={editSite === 'new' ? null : editSite} regions={regions.data ?? []}
          onClose={() => setEditSite(null)} onSaved={() => { void sites.reload(); void reloadMe(); }} />
      )}
    </>
  );
}
