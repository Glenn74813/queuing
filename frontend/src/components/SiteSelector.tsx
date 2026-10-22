import { useId } from 'react';
import { useSite } from '../auth/SiteContext';

/** Dropdown bound to the global selected site (from me.sites). */
export function SiteSelector({ className = '' }: { className?: string }) {
  const id = useId();
  const { sites, siteId, setSiteId } = useSite();
  if (sites.length === 0) return <span className="small text-body-secondary">No site assigned</span>;
  return (
    <div className={`d-flex align-items-center gap-2 ${className}`}>
      <label htmlFor={id} className="form-label mb-0 small text-nowrap">
        Site
      </label>
      <select id={id} className="form-select form-select-sm" value={siteId ?? ''} onChange={(e) => setSiteId(e.target.value)}>
        {sites.map((s) => (
          <option key={s.id} value={s.id}>
            {s.code} — {s.name}
          </option>
        ))}
      </select>
    </div>
  );
}

/** Shown on site-scoped pages when no site is available/selected. */
export function NoSiteNotice() {
  return (
    <div className="alert alert-info" role="status">
      Select a site in the top bar to continue. If none is listed, ask an administrator to assign you to a site.
    </div>
  );
}
