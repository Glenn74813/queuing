import { api, cleanParams } from './client';
import { downloadFile } from './reports';
import type { AuditEntry, AuditParams, OutboxEntry, OutboxStatus, Page, ReconciliationRow, SystemStatus } from './types';

export const auditApi = {
  list: (params: AuditParams) => api.get<Page<AuditEntry>>('/audit', { params: cleanParams(params) }).then((r) => r.data),
  export: (params: Omit<AuditParams, 'page' | 'size'>) =>
    downloadFile('/audit/export', params, `qms-audit-${params.from}_${params.to}.csv`),
};

export const systemApi = {
  status: () => api.get<SystemStatus>('/system/status').then((r) => r.data),
  outbox: (params: { status?: OutboxStatus | ''; page?: number; size?: number }) =>
    api.get<Page<OutboxEntry>>('/sync/outbox', { params: cleanParams(params) }).then((r) => r.data),
  retryOutbox: (id: string) => api.post<void>(`/sync/outbox/${id}/retry`).then(() => undefined),
  /** Central node only. */
  reconciliation: (params: { from: string; to: string; onlyProblems?: boolean }) =>
    api.get<ReconciliationRow[]>('/sync/reconciliation', { params: cleanParams(params) }).then((r) => r.data),
  /** Site node only: queue this site's daily summaries now. */
  reportReconciliationNow: () => api.post<void>('/sync/reconciliation/report-now').then(() => undefined),
};
