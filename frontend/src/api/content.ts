import { api, cleanParams } from './client';
import type { Content, ContentInput, ContentStatus } from './types';

export const contentApi = {
  list: (params: { siteId?: string; status?: ContentStatus | '' }) =>
    api.get<Content[]>('/content', { params: cleanParams(params) }).then((r) => r.data),
  /** Multipart create: part `data` is the JSON ContentInput (as an application/json Blob), part `file` optional. */
  create: (input: ContentInput, file?: File | null) => {
    const form = new FormData();
    form.append('data', new Blob([JSON.stringify(input)], { type: 'application/json' }));
    if (file) form.append('file', file);
    return api.post<Content>('/content', form).then((r) => r.data);
  },
  update: (id: string, input: ContentInput) => api.put<Content>(`/content/${id}`, input).then((r) => r.data),
  submit: (id: string) => api.post<Content>(`/content/${id}/submit`).then((r) => r.data),
  approve: (id: string) => api.post<Content>(`/content/${id}/approve`).then((r) => r.data),
  reject: (id: string, reason: string) => api.post<Content>(`/content/${id}/reject`, { reason }).then((r) => r.data),
  archive: (id: string) => api.post<Content>(`/content/${id}/archive`).then((r) => r.data),
};
