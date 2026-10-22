import { api, cleanParams } from './client';
import type { Region, RegionInput, Site, SiteInput } from './types';

export const orgApi = {
  listRegions: () => api.get<Region[]>('/regions').then((r) => r.data),
  createRegion: (body: RegionInput) => api.post<Region>('/regions', body).then((r) => r.data),
  updateRegion: (id: string, body: Required<RegionInput>) => api.put<Region>(`/regions/${id}`, body).then((r) => r.data),
  listSites: (regionId?: string) =>
    api.get<Site[]>('/sites', { params: cleanParams({ regionId }) }).then((r) => r.data),
  createSite: (body: SiteInput) => api.post<Site>('/sites', body).then((r) => r.data),
  updateSite: (id: string, body: SiteInput & { active: boolean }) => api.put<Site>(`/sites/${id}`, body).then((r) => r.data),
};
