import type { AssignmentInput, Assignment, Region, Role, Site } from '../../api/types';

interface Props {
  value: AssignmentInput[];
  onChange: (v: AssignmentInput[]) => void;
  roles: Role[];
  regions: Region[];
  sites: Site[];
}

/** Converts existing assignments to the input shape used by PUT /users/{id}/assignments. */
export function toAssignmentInputs(assignments: Assignment[]): AssignmentInput[] {
  return assignments.map((a) => ({ roleCode: a.roleCode, regionId: a.regionId, siteId: a.siteId }));
}

/** Validates that each assignment carries the scope its role requires. */
export function assignmentsError(value: AssignmentInput[], roles: Role[]): string | null {
  if (value.length === 0) return 'Add at least one role assignment.';
  for (const a of value) {
    const role = roles.find((r) => r.code === a.roleCode);
    if (!role) return 'Select a role for every assignment.';
    if (role.scopeType === 'REGION' && !a.regionId) return `${role.name} requires a region.`;
    if (role.scopeType === 'SITE' && !a.siteId) return `${role.name} requires a site.`;
  }
  return null;
}

/** Editable list of role assignments; the scope selector follows the role's scopeType. */
export function AssignmentsEditor({ value, onChange, roles, regions, sites }: Props) {
  const update = (i: number, patch: Partial<AssignmentInput>) =>
    onChange(value.map((a, idx) => (idx === i ? { ...a, ...patch } : a)));

  return (
    <fieldset className="mb-3">
      <legend className="form-label fs-6">Role assignments</legend>
      {value.map((a, i) => {
        const role = roles.find((r) => r.code === a.roleCode);
        return (
          <div key={i} className="row g-2 align-items-end mb-2">
            <div className="col-md-5">
              <label className="form-label small" htmlFor={`as-role-${i}`}>Role</label>
              <select id={`as-role-${i}`} className="form-select form-select-sm" value={a.roleCode}
                onChange={(e) => update(i, { roleCode: e.target.value, regionId: undefined, siteId: undefined })}>
                <option value="">Select role…</option>
                {roles.map((r) => (
                  <option key={r.code} value={r.code}>{r.name} ({r.scopeType.toLowerCase()})</option>
                ))}
              </select>
            </div>
            <div className="col-md-5">
              {role?.scopeType === 'REGION' && (
                <>
                  <label className="form-label small" htmlFor={`as-scope-${i}`}>Region</label>
                  <select id={`as-scope-${i}`} className="form-select form-select-sm" value={a.regionId ?? ''}
                    onChange={(e) => update(i, { regionId: e.target.value || undefined })}>
                    <option value="">Select region…</option>
                    {regions.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                  </select>
                </>
              )}
              {role?.scopeType === 'SITE' && (
                <>
                  <label className="form-label small" htmlFor={`as-scope-${i}`}>Site</label>
                  <select id={`as-scope-${i}`} className="form-select form-select-sm" value={a.siteId ?? ''}
                    onChange={(e) => update(i, { siteId: e.target.value || undefined })}>
                    <option value="">Select site…</option>
                    {sites.map((s) => <option key={s.id} value={s.id}>{s.code} — {s.name}</option>)}
                  </select>
                </>
              )}
              {role?.scopeType === 'NATIONAL' && <span className="small text-body-secondary">National scope</span>}
            </div>
            <div className="col-md-2">
              <button type="button" className="btn btn-sm btn-outline-danger w-100" onClick={() => onChange(value.filter((_, idx) => idx !== i))}
                aria-label={`Remove assignment ${i + 1}`}>
                Remove
              </button>
            </div>
          </div>
        );
      })}
      <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => onChange([...value, { roleCode: '' }])}>
        Add assignment
      </button>
    </fieldset>
  );
}
