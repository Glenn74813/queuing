import { useState } from 'react';
import { devicesApi } from '../../api/counters';
import type { Device, DeviceType, DeviceWithKey } from '../../api/types';
import { useSite } from '../../auth/SiteContext';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { CheckField, SelectField, TextField, enumOptions } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { PageHeader } from '../../components/PageHeader';
import { NoSiteNotice } from '../../components/SiteSelector';
import { ActiveBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime } from '../../utils/format';

/** One-time display of a freshly issued device API key. */
function ApiKeyAlert({ result, onDismiss }: { result: DeviceWithKey; onDismiss: () => void }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(result.apiKey);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <div className="alert alert-warning" role="alert">
      <h2 className="h6">API key for {result.device.name} ({result.device.code})</h2>
      <p className="mb-2">
        <strong>Copy this key now — it will not be shown again.</strong> Enter it on the device's setup screen
        ({result.device.type === 'KIOSK' ? '/kiosk' : '/display'}).
      </p>
      <div className="input-group mb-2">
        <label htmlFor="api-key" className="visually-hidden">Device API key</label>
        <input id="api-key" className="form-control font-monospace" readOnly value={result.apiKey} onFocus={(e) => e.target.select()} />
        <button type="button" className="btn btn-outline-dark" onClick={copy}>{copied ? 'Copied' : 'Copy'}</button>
      </div>
      <button type="button" className="btn btn-sm btn-dark" onClick={onDismiss}>I have saved the key</button>
    </div>
  );
}

export function DevicesPage() {
  const { site, siteId } = useSite();
  const devices = useAsync(() => devicesApi.list(siteId as string), [siteId], !!siteId);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Device | null>(null);
  const [rotating, setRotating] = useState<Device | null>(null);
  const [issued, setIssued] = useState<DeviceWithKey | null>(null);
  if (!siteId) return <NoSiteNotice />;
  const reload = () => void devices.reload();

  return (
    <>
      <PageHeader title="Devices" subtitle={`Kiosks and queue displays · ${site?.name ?? ''}`}
        actions={<button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>Register device</button>} />
      {issued && <ApiKeyAlert result={issued} onDismiss={() => setIssued(null)} />}
      <ErrorAlert error={devices.error} />
      <DataTable caption="Devices" loading={devices.loading} rows={devices.data} rowKey={(d) => d.id}
        columns={[
          { key: 'code', header: 'Code' },
          { key: 'name', header: 'Name' },
          { key: 'type', header: 'Type' },
          { key: 'lastSeenAt', header: 'Last seen', render: (d) => formatDateTime(d.lastSeenAt) },
          { key: 'active', header: 'Status', render: (d) => <ActiveBadge active={d.active} /> },
          { key: 'actions', header: <span className="visually-hidden">Actions</span>, className: 'text-end text-nowrap',
            render: (d) => (
              <div className="btn-group btn-group-sm">
                <button type="button" className="btn btn-outline-primary" onClick={() => setEditing(d)}>Edit</button>
                <button type="button" className="btn btn-outline-warning" onClick={() => setRotating(d)}>Rotate key</button>
              </div>
            ) },
        ]} />

      {creating && <CreateDeviceForm siteId={siteId} onClose={() => setCreating(false)} onCreated={(r) => { setIssued(r); reload(); }} />}
      {editing && <EditDeviceForm siteId={siteId} device={editing} onClose={() => setEditing(null)} onSaved={reload} />}
      {rotating && (
        <ConfirmDialog title={`Rotate key for ${rotating.name}`} variant="warning" confirmLabel="Rotate key"
          message="The current key stops working immediately; the device must be set up again with the new key."
          onConfirm={async () => {
            setIssued(await devicesApi.rotateKey(siteId, rotating.id));
            reload();
          }}
          onClose={() => setRotating(null)} />
      )}
    </>
  );
}

function CreateDeviceForm({ siteId, onClose, onCreated }: { siteId: string; onClose: () => void; onCreated: (r: DeviceWithKey) => void }) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [type, setType] = useState<DeviceType>('KIOSK');
  return (
    <FormModal title="Register device" submitLabel="Register" onClose={onClose}
      validate={() => (!code || !name ? 'Code and name are required.' : null)}
      onSubmit={async () => onCreated(await devicesApi.create(siteId, { code, name, type }))}>
      {(fe) => (
        <>
          <TextField label="Code" required value={code} onChange={setCode} error={fe('code')} />
          <TextField label="Name" required value={name} onChange={setName} error={fe('name')} />
          <SelectField label="Type" value={type} onChange={(v) => setType(v as DeviceType)} options={enumOptions(['KIOSK', 'DISPLAY'])} />
        </>
      )}
    </FormModal>
  );
}

function EditDeviceForm({ siteId, device, onClose, onSaved }: { siteId: string; device: Device; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(device.name);
  const [active, setActive] = useState(device.active);
  return (
    <FormModal title={`Edit ${device.code}`} onClose={onClose} validate={() => (!name ? 'Name is required.' : null)}
      onSubmit={async () => {
        await devicesApi.update(siteId, device.id, { name, active });
        onSaved();
      }}>
      {(fe) => (
        <>
          <TextField label="Name" required value={name} onChange={setName} error={fe('name')} />
          <CheckField label="Active" checked={active} onChange={setActive} />
        </>
      )}
    </FormModal>
  );
}
