import { api, cleanParams } from './client';
import type {
  AgentPerformanceReport,
  ExceptionRow,
  Page,
  ReportParams,
  ReportRun,
  ReportSchedule,
  ReportScheduleInput,
  SummaryReport,
  TicketReportRow,
} from './types';

export type ExportFormat = 'csv' | 'xlsx' | 'pdf';

/** Downloads through axios (so the bearer token is attached) and returns the blob + server filename. */
export async function downloadFile(path: string, params: object, fallbackName: string) {
  const res = await api.get<Blob>(path, { params: cleanParams(params), responseType: 'blob' });
  const disposition = String(res.headers['content-disposition'] ?? '');
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  return { blob: res.data, filename: match ? decodeURIComponent(match[1]) : fallbackName };
}

export const reportSchedulesApi = {
  list: () => api.get<ReportSchedule[]>('/report-schedules').then((r) => r.data),
  create: (body: ReportScheduleInput) => api.post<ReportSchedule>('/report-schedules', body).then((r) => r.data),
  update: (id: string, body: ReportScheduleInput) =>
    api.put<ReportSchedule>(`/report-schedules/${id}`, body).then((r) => r.data),
  runNow: (id: string) => api.post<ReportRun>(`/report-schedules/${id}/run-now`).then((r) => r.data),
  runs: (id: string) => api.get<ReportRun[]>(`/report-schedules/${id}/runs`).then((r) => r.data),
  download: (run: ReportRun, schedule: ReportSchedule) =>
    downloadFile(`/report-runs/${run.id}/download`, {},
      `${schedule.name}_${run.periodFrom}_${run.periodTo}.${schedule.format.toLowerCase()}`),
};

export const reportsApi = {
  summary: (params: ReportParams) =>
    api.get<SummaryReport>('/reports/summary', { params: cleanParams(params) }).then((r) => r.data),
  tickets: (params: ReportParams & { page?: number; size?: number }) =>
    api.get<Page<TicketReportRow>>('/reports/tickets', { params: cleanParams(params) }).then((r) => r.data),
  exceptions: (params: ReportParams) =>
    api.get<ExceptionRow[]>('/reports/exceptions', { params: cleanParams(params) }).then((r) => r.data),
  exportSummary: (params: ReportParams, format: ExportFormat) =>
    downloadFile('/reports/summary/export', { ...params, format }, `qms-summary-${params.from}_${params.to}.${format}`),
  agentPerformance: (params: ReportParams) =>
    api.get<AgentPerformanceReport>('/reports/agent-performance', {
      params: cleanParams({ from: params.from, to: params.to, regionId: params.regionId, siteId: params.siteId }),
    }).then((r) => r.data),
  exportAgentPerformance: (params: ReportParams, format: ExportFormat) =>
    downloadFile('/reports/agent-performance/export',
      { from: params.from, to: params.to, regionId: params.regionId, siteId: params.siteId, format },
      `qms-agent-performance-${params.from}_${params.to}.${format}`),
  /** Every ticket row of the filter (server limit 100,000) as CSV. */
  exportTickets: (params: ReportParams) =>
    downloadFile('/reports/tickets/export', { ...params, groupBy: undefined }, `qms-tickets-${params.from}_${params.to}.csv`),
};
