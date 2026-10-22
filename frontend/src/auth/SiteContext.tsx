import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { SiteRef } from '../api/types';
import { useAuth } from './AuthContext';

const SITE_KEY = 'qms.selectedSiteId';

interface SiteContextValue {
  sites: SiteRef[];
  site: SiteRef | null;
  siteId: string | null;
  setSiteId: (id: string) => void;
}

const SiteContext = createContext<SiteContextValue | null>(null);

/** Remembers whether the Dashboard shows one site or all sites (per browser tab). */
export const DASHBOARD_SCOPE_KEY = 'qms.dashboardScope';

/** Holds the currently selected site (from me.sites) for site-scoped pages. Remembered per browser tab. */
export function SiteProvider({ children }: { children: ReactNode }) {
  const { me } = useAuth();
  const sites = useMemo(() => me?.sites ?? [], [me]);
  const [siteId, setSiteIdState] = useState<string | null>(() => {
    try {
      return sessionStorage.getItem(SITE_KEY);
    } catch {
      return null;
    }
  });

  // Keep the selection valid when the user's site list changes (login / logout).
  useEffect(() => {
    if (sites.length === 0) return;
    if (!siteId || !sites.some((s) => s.id === siteId)) setSiteIdState(sites[0].id);
  }, [sites, siteId]);

  const value = useMemo<SiteContextValue>(() => {
    const site = sites.find((s) => s.id === siteId) ?? null;
    return {
      sites,
      site,
      siteId: site?.id ?? null,
      setSiteId: (id: string) => {
        setSiteIdState(id);
        try {
          sessionStorage.setItem(SITE_KEY, id);
          // Picking a site is a request to see that site: the Dashboard follows it.
          sessionStorage.setItem(DASHBOARD_SCOPE_KEY, 'site');
        } catch {
          /* ignore */
        }
      },
    };
  }, [sites, siteId]);

  return <SiteContext.Provider value={value}>{children}</SiteContext.Provider>;
}

export function useSite(): SiteContextValue {
  const ctx = useContext(SiteContext);
  if (!ctx) throw new Error('useSite must be used inside <SiteProvider>');
  return ctx;
}
