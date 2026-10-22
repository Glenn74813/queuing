import { api } from './client';
import type {
  PriorityCategory,
  PriorityCategoryInput,
  ServiceItem,
  ServiceItemInput,
  ServiceItemVersion,
  SiteService,
  SiteServiceInput,
} from './types';

export const catalogApi = {
  listServiceItems: () => api.get<ServiceItem[]>('/service-items').then((r) => r.data),
  createServiceItem: (body: ServiceItemInput) => api.post<ServiceItem>('/service-items', body).then((r) => r.data),
  updateServiceItem: (id: string, body: ServiceItemInput) =>
    api.put<ServiceItem>(`/service-items/${id}`, body).then((r) => r.data),
  serviceItemHistory: (id: string) =>
    api.get<ServiceItemVersion[]>(`/service-items/${id}/history`).then((r) => r.data),

  listSiteServices: (siteId: string) => api.get<SiteService[]>(`/sites/${siteId}/services`).then((r) => r.data),
  createSiteService: (siteId: string, body: SiteServiceInput) =>
    api.post<SiteService>(`/sites/${siteId}/services`, body).then((r) => r.data),
  /** Adds every active catalog service the site does not offer yet; returns the services created. */
  addCatalogServices: (siteId: string) =>
    api.post<SiteService[]>(`/sites/${siteId}/services/add-catalog`, {}).then((r) => r.data),
  updateSiteService: (siteId: string, id: string, body: SiteServiceInput) =>
    api.put<SiteService>(`/sites/${siteId}/services/${id}`, body).then((r) => r.data),

  listPriorities: () => api.get<PriorityCategory[]>('/priority-categories').then((r) => r.data),
  createPriority: (body: PriorityCategoryInput) =>
    api.post<PriorityCategory>('/priority-categories', body).then((r) => r.data),
  updatePriority: (id: string, body: PriorityCategoryInput) =>
    api.put<PriorityCategory>(`/priority-categories/${id}`, body).then((r) => r.data),
};
