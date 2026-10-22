/**
 * Token storage policy:
 *  - access token lives only in memory (never persisted, gone on reload);
 *  - refresh token lives in sessionStorage (survives reload within the tab, cleared when the tab closes).
 */
const REFRESH_KEY = 'qms.refreshToken';

let accessToken: string | null = null;

function safeSession(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.sessionStorage : null;
  } catch {
    return null;
  }
}

export const tokenStore = {
  getAccessToken: (): string | null => accessToken,
  setAccessToken: (token: string | null): void => {
    accessToken = token;
  },
  getRefreshToken: (): string | null => safeSession()?.getItem(REFRESH_KEY) ?? null,
  setRefreshToken: (token: string | null): void => {
    const s = safeSession();
    if (!s) return;
    if (token) s.setItem(REFRESH_KEY, token);
    else s.removeItem(REFRESH_KEY);
  },
  clear: (): void => {
    accessToken = null;
    safeSession()?.removeItem(REFRESH_KEY);
  },
};
