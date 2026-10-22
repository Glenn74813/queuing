import { useId } from 'react';
import { catalogApi } from '../api/catalog';
import { useAsync } from '../hooks/useAsync';
import { ErrorAlert } from './ErrorAlert';

/** Target-service picker rendered inside the transfer ReasonDialog. */
export function TransferPicker({
  siteId,
  excludeServiceId,
  value,
  onChange,
}: {
  siteId: string;
  excludeServiceId: string;
  value: string;
  onChange: (v: string) => void;
}) {
  const id = useId();
  const services = useAsync(() => catalogApi.listSiteServices(siteId), [siteId]);
  const options = (services.data ?? []).filter((s) => s.active && s.id !== excludeServiceId);
  return (
    <div className="mb-3">
      <ErrorAlert error={services.error} />
      <label htmlFor={id} className="form-label">
        Transfer to service <span aria-hidden="true">*</span>
      </label>
      <select id={id} className="form-select" required value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Select a service…</option>
        {options
          .slice()
          .sort((a, b) => a.sortOrder - b.sortOrder)
          .map((s) => (
            <option key={s.id} value={s.id}>
              {s.serviceName} ({s.prefix})
            </option>
          ))}
      </select>
    </div>
  );
}
