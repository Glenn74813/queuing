import { api } from './client';
import type { Counter, CounterInput, Device, DeviceCreateInput, DeviceUpdateInput, DeviceWithKey } from './types';

export const countersApi = {
  list: (siteId: string) => api.get<Counter[]>(`/sites/${siteId}/counters`).then((r) => r.data),
  /** Supervisor intervention: signs the agent out; their tickets return to the queue in place. */
  forceEndSession: (siteId: string, counterId: string, reason: string) =>
    api.post<void>(`/sites/${siteId}/counters/${counterId}/force-end-session`, { reason }).then(() => undefined),
  create: (siteId: string, body: CounterInput) => api.post<Counter>(`/sites/${siteId}/counters`, body).then((r) => r.data),
  update: (siteId: string, id: string, body: CounterInput) =>
    api.put<Counter>(`/sites/${siteId}/counters/${id}`, body).then((r) => r.data),
};

export const devicesApi = {
  list: (siteId: string) => api.get<Device[]>(`/sites/${siteId}/devices`).then((r) => r.data),
  create: (siteId: string, body: DeviceCreateInput) =>
    api.post<DeviceWithKey>(`/sites/${siteId}/devices`, body).then((r) => r.data),
  rotateKey: (siteId: string, id: string) =>
    api.post<DeviceWithKey>(`/sites/${siteId}/devices/${id}/rotate-key`).then((r) => r.data),
  update: (siteId: string, id: string, body: DeviceUpdateInput) =>
    api.put<Device>(`/sites/${siteId}/devices/${id}`, body).then((r) => r.data),
};
