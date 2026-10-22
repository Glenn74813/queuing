import { api } from './client';
import type { ChangePasswordRequest, LoginRequest, Me, TokenResponse } from './types';

export const authApi = {
  login: (body: LoginRequest) => api.post<TokenResponse>('/auth/login', body).then((r) => r.data),
  logout: (refreshToken: string) => api.post<void>('/auth/logout', { refreshToken }).then(() => undefined),
  me: () => api.get<Me>('/auth/me').then((r) => r.data),
  changePassword: (body: ChangePasswordRequest) =>
    api.post<void>('/auth/change-password', body).then(() => undefined),
};
