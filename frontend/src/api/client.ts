import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { tokenStore } from '../auth/tokenStore';
import type { ApiErrorBody, TokenResponse } from './types';

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL || '/api/v1';

/** Axios instance for authenticated staff endpoints. */
export const api: AxiosInstance = axios.create({ baseURL: API_BASE_URL });

/** Bare instance used for the refresh call so it never re-enters the interceptors. */
const refreshClient: AxiosInstance = axios.create({ baseURL: API_BASE_URL });

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean };

/** Callbacks wired by AuthContext so the HTTP layer can report session changes. */
interface AuthHooks {
  onTokens: (tokens: TokenResponse) => void;
  onAuthFailure: () => void;
}
let authHooks: AuthHooks = { onTokens: () => undefined, onAuthFailure: () => undefined };
export function setAuthHooks(hooks: AuthHooks): void {
  authHooks = hooks;
}

// Single in-flight refresh shared by all concurrent 401s.
let refreshPromise: Promise<string | null> | null = null;

/** Exchanges the stored refresh token for a new token pair. Resolves to the new access token or null. */
export function refreshAccessToken(): Promise<string | null> {
  if (refreshPromise) return refreshPromise;
  const refreshToken = tokenStore.getRefreshToken();
  if (!refreshToken) return Promise.resolve(null);
  refreshPromise = refreshClient
    .post<TokenResponse>('/auth/refresh', { refreshToken })
    .then((res) => {
      tokenStore.setAccessToken(res.data.accessToken);
      tokenStore.setRefreshToken(res.data.refreshToken);
      authHooks.onTokens(res.data);
      return res.data.accessToken;
    })
    .catch(() => null)
    .finally(() => {
      refreshPromise = null;
    });
  return refreshPromise;
}

api.interceptors.request.use((config) => {
  const token = tokenStore.getAccessToken();
  if (token) config.headers.set('Authorization', `Bearer ${token}`);
  return config;
});

api.interceptors.response.use(
  (res) => res,
  async (error: AxiosError) => {
    const original = error.config as RetriableConfig | undefined;
    const url = original?.url ?? '';
    const isAuthCall = url.includes('/auth/login') || url.includes('/auth/refresh');
    if (error.response?.status === 401 && original && !original._retried && !isAuthCall) {
      // Try exactly one refresh, then replay the original request once.
      original._retried = true;
      const newToken = await refreshAccessToken();
      if (newToken) {
        original.headers.set('Authorization', `Bearer ${newToken}`);
        return api.request(original);
      }
      tokenStore.clear();
      authHooks.onAuthFailure();
    }
    return Promise.reject(error);
  },
);

/**
 * Normalizes any thrown value into the contract's error body so components can render it uniformly.
 * Network failures (no response) are reported with status 0.
 */
export function toApiError(err: unknown): ApiErrorBody {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as Partial<ApiErrorBody> | undefined;
    if (err.response && data && typeof data === 'object' && 'message' in data) {
      return { status: err.response.status, ...data, message: String(data.message ?? err.message) };
    }
    if (err.response) {
      return { status: err.response.status, message: err.response.statusText || err.message };
    }
    return { status: 0, code: 'NETWORK_ERROR', message: 'Unable to reach the server. Check your connection.' };
  }
  if (err instanceof Error) return { status: 0, message: err.message };
  return { status: 0, message: 'Unexpected error' };
}

/** True when the error is a transport failure (no HTTP response), i.e. safe to retry idempotent calls. */
export function isNetworkError(err: unknown): boolean {
  return axios.isAxiosError(err) && !err.response;
}

/** Removes empty-string/undefined/null params so they are not sent as `?x=`. */
export function cleanParams<T extends object>(params: T): Partial<T> {
  const out: Partial<T> = {};
  (Object.keys(params) as (keyof T)[]).forEach((k) => {
    const v = params[k];
    if (v !== undefined && v !== null && v !== '') out[k] = v;
  });
  return out;
}
