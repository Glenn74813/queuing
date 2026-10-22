import type { AxiosResponse } from 'axios';
import { api } from './client';
import type { AgentSession, DashboardOverview, QueueSnapshot, ServiceVolumeReport, Ticket, TicketDetail } from './types';

/** Several agent endpoints answer 204 (no content) instead of a body; map that to null. */
function bodyOrNull<T>(res: AxiosResponse<T>): T | null {
  return res.status === 204 || res.data === '' || res.data == null ? null : res.data;
}

export const agentApi = {
  getSession: () => api.get<AgentSession>('/agent/session').then(bodyOrNull),
  startSession: (counterId: string) =>
    api.post<AgentSession>('/agent/session/start', { counterId }).then((r) => r.data),
  endSession: () => api.post<void>('/agent/session/end').then(() => undefined),
  startBreak: (reason: string) => api.post<AgentSession>('/agent/session/break/start', { reason }).then((r) => r.data),
  endBreak: () => api.post<AgentSession>('/agent/session/break/end').then((r) => r.data),
  currentTicket: () => api.get<Ticket>('/agent/current-ticket').then(bodyOrNull),
  heldTickets: () => api.get<Ticket[]>('/agent/held-tickets').then((r) => r.data ?? []),
  callNext: () => api.post<Ticket>('/agent/call-next').then(bodyOrNull),
};

/** Ticket state transitions (QUEUE_SERVE / QUEUE_SUPERVISE / TICKET_VOID). */
export const ticketsApi = {
  get: (id: string) => api.get<TicketDetail>(`/tickets/${id}`).then((r) => r.data),
  recall: (id: string) => api.post<Ticket>(`/tickets/${id}/recall`).then((r) => r.data),
  complete: (id: string) => api.post<Ticket>(`/tickets/${id}/complete`).then((r) => r.data),
  hold: (id: string, reason: string) => api.post<Ticket>(`/tickets/${id}/hold`, { reason }).then((r) => r.data),
  resume: (id: string) => api.post<Ticket>(`/tickets/${id}/resume`).then((r) => r.data),
  transfer: (id: string, targetSiteServiceId: string, reason: string) =>
    api.post<Ticket>(`/tickets/${id}/transfer`, { targetSiteServiceId, reason }).then((r) => r.data),
  /** Sends the ticket to the next stage configured for its current service. */
  nextStage: (id: string) => api.post<Ticket>(`/tickets/${id}/next-stage`).then((r) => r.data),
  remove: (id: string, reason: string) => api.post<Ticket>(`/tickets/${id}/remove`, { reason }).then((r) => r.data),
  void: (id: string, reason: string) => api.post<Ticket>(`/tickets/${id}/void`, { reason }).then((r) => r.data),
};

export const queueApi = {
  snapshot: (siteId: string) => api.get<QueueSnapshot>(`/sites/${siteId}/queue`).then((r) => r.data),
  /** All sites in the user's scope: today's figures per site, and clients per catalog service over `days`. */
  overview: (days: number) =>
    api.get<DashboardOverview>('/dashboard/overview', { params: { days } }).then((r) => r.data),
  /** Clients per service over the last `days` business days (today included). */
  serviceVolume: (siteId: string, days: number) =>
    api.get<ServiceVolumeReport>(`/sites/${siteId}/service-volume`, { params: { days } }).then((r) => r.data),
};
