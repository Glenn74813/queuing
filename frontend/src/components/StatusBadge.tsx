const VARIANTS: Record<string, string> = {
  // tickets
  WAITING: 'secondary',
  CALLED: 'primary',
  ON_HOLD: 'warning',
  COMPLETED: 'success',
  REMOVED: 'dark',
  VOIDED: 'danger',
  EXPIRED: 'light',
  // users
  ACTIVE: 'success',
  SUSPENDED: 'warning',
  DEACTIVATED: 'secondary',
  // content
  DRAFT: 'secondary',
  PENDING_APPROVAL: 'info',
  PUBLISHED: 'success',
  REJECTED: 'danger',
  ARCHIVED: 'dark',
  // agent / outbox
  AVAILABLE: 'success',
  ON_BREAK: 'warning',
  PENDING: 'info',
  SENT: 'success',
  SUCCESS: 'success',
  FAILED: 'danger',
  // sync reconciliation
  MATCH: 'success',
  IN_TRANSIT: 'info',
  MISMATCH: 'danger',
  // SLA
  MET: 'success',
  NOT_MET: 'danger',
  NO_DATA: 'secondary',
  // booleans
  INACTIVE: 'secondary',
};

/** Coloured badge for any status-like enum value; text is always shown so colour is not the only cue. */
export function StatusBadge({ status }: { status: string | undefined | null }) {
  if (!status) return <span className="text-body-secondary">—</span>;
  const variant = VARIANTS[status] ?? 'secondary';
  const textClass = ['warning', 'info', 'light'].includes(variant) ? 'text-dark' : '';
  return <span className={`badge text-bg-${variant} ${textClass}`}>{status.replace(/_/g, ' ')}</span>;
}

export function ActiveBadge({ active }: { active: boolean }) {
  return <StatusBadge status={active ? 'ACTIVE' : 'INACTIVE'} />;
}
