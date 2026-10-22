import { api } from './client';
import type { SlaPolicy, SlaPolicyInput } from './types';

export const slaApi = {
  list: () => api.get<SlaPolicy[]>('/sla-policies').then((r) => r.data),
  saveNational: (body: SlaPolicyInput) => api.put<SlaPolicy>('/sla-policies/national', body).then((r) => r.data),
  saveRegion: (regionId: string, body: SlaPolicyInput) =>
    api.put<SlaPolicy>(`/sla-policies/regions/${regionId}`, body).then((r) => r.data),
  /** The region goes back to the national default. */
  removeRegion: (regionId: string) => api.delete<void>(`/sla-policies/regions/${regionId}`).then(() => undefined),
};
