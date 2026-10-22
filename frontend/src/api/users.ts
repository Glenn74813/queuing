import { api, cleanParams } from './client';
import type { AssignmentInput, Page, Role, User, UserCreateInput, UserStatus, UserUpdateInput } from './types';

export const usersApi = {
  list: (params: { q?: string; page?: number; size?: number }) =>
    api.get<Page<User>>('/users', { params: cleanParams(params) }).then((r) => r.data),
  create: (body: UserCreateInput) => api.post<User>('/users', body).then((r) => r.data),
  update: (id: string, body: UserUpdateInput) => api.put<User>(`/users/${id}`, body).then((r) => r.data),
  changeStatus: (id: string, status: UserStatus, reason: string) =>
    api.post<User>(`/users/${id}/status`, { status, reason }).then((r) => r.data),
  setAssignments: (id: string, assignments: AssignmentInput[]) =>
    api.put<User>(`/users/${id}/assignments`, assignments).then((r) => r.data),
  resetPassword: (id: string, newPassword: string) =>
    api.post<void>(`/users/${id}/reset-password`, { newPassword }).then(() => undefined),
  listRoles: () => api.get<Role[]>('/roles').then((r) => r.data),
};
