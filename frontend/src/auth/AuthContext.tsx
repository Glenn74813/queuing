import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { authApi } from '../api/auth';
import { refreshAccessToken, setAuthHooks } from '../api/client';
import type { Me, Permission } from '../api/types';
import { hasAnyPermission } from './permissions';
import { tokenStore } from './tokenStore';

export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

export interface AuthContextValue {
  status: AuthStatus;
  me: Me | null;
  login: (username: string, password: string) => Promise<Me>;
  logout: () => Promise<void>;
  /** Re-reads /auth/me (e.g. after changing password). */
  reloadMe: () => Promise<Me | null>;
  can: (...anyOf: Permission[]) => boolean;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');

  const clearSession = useCallback(() => {
    tokenStore.clear();
    setMe(null);
    setStatus('anonymous');
  }, []);

  // Let the axios layer push refreshed tokens / forced logouts into React state.
  useEffect(() => {
    setAuthHooks({
      onTokens: (t) => {
        setMe(t.user);
        setStatus('authenticated');
      },
      onAuthFailure: clearSession,
    });
  }, [clearSession]);

  // Restore the session after a page reload using the refresh token from sessionStorage.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const token = tokenStore.getAccessToken() ?? (await refreshAccessToken());
      if (cancelled) return;
      if (!token) {
        clearSession();
        return;
      }
      try {
        const current = await authApi.me();
        if (!cancelled) {
          setMe(current);
          setStatus('authenticated');
        }
      } catch {
        if (!cancelled) clearSession();
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [clearSession]);

  const login = useCallback(async (username: string, password: string) => {
    const res = await authApi.login({ username, password });
    tokenStore.setAccessToken(res.accessToken);
    tokenStore.setRefreshToken(res.refreshToken);
    setMe(res.user);
    setStatus('authenticated');
    return res.user;
  }, []);

  const logout = useCallback(async () => {
    const refreshToken = tokenStore.getRefreshToken();
    try {
      if (refreshToken) await authApi.logout(refreshToken);
    } catch {
      /* best effort: the local session is cleared regardless */
    }
    clearSession();
  }, [clearSession]);

  const reloadMe = useCallback(async () => {
    try {
      const current = await authApi.me();
      setMe(current);
      return current;
    } catch {
      return null;
    }
  }, []);

  // Sites and permissions can change while the user is signed in (a site is created, a role is
  // granted). Re-read them when the user comes back to the tab, at most every 30 seconds.
  const lastMeRefresh = useRef(0);
  useEffect(() => {
    if (status !== 'authenticated') return;
    const refreshIfStale = () => {
      if (document.visibilityState !== 'visible' || Date.now() - lastMeRefresh.current < 30_000) return;
      lastMeRefresh.current = Date.now();
      void reloadMe();
    };
    lastMeRefresh.current = Date.now();
    window.addEventListener('focus', refreshIfStale);
    document.addEventListener('visibilitychange', refreshIfStale);
    return () => {
      window.removeEventListener('focus', refreshIfStale);
      document.removeEventListener('visibilitychange', refreshIfStale);
    };
  }, [status, reloadMe]);

  const can = useCallback((...anyOf: Permission[]) => hasAnyPermission(me?.permissions, anyOf), [me]);

  const value = useMemo<AuthContextValue>(
    () => ({ status, me, login, logout, reloadMe, can }),
    [status, me, login, logout, reloadMe, can],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
