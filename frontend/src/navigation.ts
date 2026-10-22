import type { Permission } from './api/types';

export interface NavItem {
  to: string;
  label: string;
  /** Link is shown when the user has at least one of these. */
  anyOf: Permission[];
  group: 'Queue' | 'Administration' | 'Content & Reports' | 'System';
}

/** Single source of truth for sidebar links and their route guards. */
export const NAV_ITEMS: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', anyOf: ['QUEUE_VIEW'], group: 'Queue' },
  { to: '/agent', label: 'Agent Console', anyOf: ['QUEUE_SERVE'], group: 'Queue' },
  { to: '/monitor', label: 'Queue Monitor', anyOf: ['QUEUE_VIEW', 'QUEUE_SUPERVISE'], group: 'Queue' },
  { to: '/admin/org', label: 'Regions & Sites', anyOf: ['SITE_MANAGE', 'REGION_MANAGE'], group: 'Administration' },
  { to: '/admin/users', label: 'Users', anyOf: ['USER_VIEW', 'USER_MANAGE'], group: 'Administration' },
  { to: '/admin/catalog', label: 'Service Catalog', anyOf: ['CATALOG_MANAGE'], group: 'Administration' },
  { to: '/admin/site-services', label: 'Site Services', anyOf: ['SITE_SERVICE_MANAGE'], group: 'Administration' },
  { to: '/admin/priorities', label: 'Priority Categories', anyOf: ['PRIORITY_MANAGE'], group: 'Administration' },
  { to: '/admin/counters', label: 'Counters', anyOf: ['COUNTER_MANAGE'], group: 'Administration' },
  { to: '/admin/devices', label: 'Devices', anyOf: ['DEVICE_MANAGE'], group: 'Administration' },
  { to: '/admin/sla', label: 'SLA Targets', anyOf: ['SLA_MANAGE'], group: 'Administration' },
  { to: '/content', label: 'Content', anyOf: ['CONTENT_EDIT', 'CONTENT_APPROVE'], group: 'Content & Reports' },
  { to: '/reports', label: 'Reports', anyOf: ['REPORT_VIEW'], group: 'Content & Reports' },
  { to: '/reports/scheduled', label: 'Scheduled Reports', anyOf: ['REPORT_VIEW'], group: 'Content & Reports' },
  { to: '/audit', label: 'Audit Log', anyOf: ['AUDIT_VIEW'], group: 'System' },
  { to: '/system', label: 'System & Sync', anyOf: ['SYSTEM_MONITOR', 'SYNC_MANAGE'], group: 'System' },
];

export const NAV_GROUPS: NavItem['group'][] = ['Queue', 'Administration', 'Content & Reports', 'System'];
