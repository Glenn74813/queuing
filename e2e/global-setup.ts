/**
 * Seeds the system under test through the REST API only (no SQL), as the bootstrap superadmin:
 * region, site E2E, two site services, two counters, an agent and a supervisor, a kiosk and a
 * display device. Every step is find-or-create, so it also works when re-run against the same
 * database (device keys are rotated, user passwords reset). Results go to .auth/state.json.
 */
import { Api, ApiError } from './support/api';
import { API_URL, BOOTSTRAP_PASSWORD, SUPERADMIN_PASSWORD } from './support/env';
import { saveState, type SeedState, type SeedUser } from './support/state';

const REGION = { code: 'E2E-R', name: 'E2E Test Region' };
const SITE = { code: 'E2E', name: 'E2E Test LHIO' };
const AGENT: SeedUser = {
  username: 'e2e.agent',
  fullName: 'Ana Agent',
  tempPassword: 'Temp-Agent-Pass-1',
  password: 'Agent-E2e-Final-2',
};
const SUPERVISOR: SeedUser = {
  username: 'e2e.supervisor',
  fullName: 'Sam Supervisor',
  tempPassword: 'Temp-Superv-Pass-1',
  password: 'Superv-E2e-Final-2',
};

interface Region { id: string; code: string }
interface Site { id: string; code: string; name: string }
interface ServiceItem { id: string; code: string; name: string }
interface SiteService { id: string; serviceCode: string; serviceName: string; prefix: string }
interface Priority { id: string; code: string; name: string }
interface Counter { id: string; code: string; name: string }
interface Device { id: string; code: string; type: string }
interface User { id: string; username: string }

async function waitForBackend(): Promise<void> {
  const deadline = Date.now() + 120_000;
  let last: unknown;
  while (Date.now() < deadline) {
    try {
      const anon = await Api.anonymous();
      try {
        // Any HTTP answer (even 401) means the API is up.
        await anon.get('/auth/me').catch((e) => {
          if (!(e instanceof ApiError)) throw e;
        });
        return;
      } finally {
        await anon.dispose();
      }
    } catch (e) {
      last = e;
      await new Promise((r) => setTimeout(r, 1000));
    }
  }
  throw new Error(`Backend at ${API_URL} is not reachable: ${String(last)}`);
}

/** Logs in as superadmin, performing the forced first-login password change when needed. */
async function superadmin(): Promise<Api> {
  try {
    const api = await Api.login('superadmin', SUPERADMIN_PASSWORD);
    if (!api.me.mustChangePassword) return api;
    await api.dispose();
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 401) throw e;
  }
  const first = await Api.login('superadmin', BOOTSTRAP_PASSWORD);
  await first.post('/auth/change-password', { currentPassword: BOOTSTRAP_PASSWORD, newPassword: SUPERADMIN_PASSWORD });
  await first.dispose();
  return Api.login('superadmin', SUPERADMIN_PASSWORD);
}

/** Creates the user (or resets its password), then does its first-login password change. */
async function ensureStaffUser(admin: Api, u: SeedUser, roleCode: string, siteId: string): Promise<void> {
  const page = await admin.get<{ content: User[] }>('/users', { q: u.username, size: 50 });
  const existing = page.content.find((x) => x.username === u.username);
  if (existing) {
    await admin.post(`/users/${existing.id}/reset-password`, { newPassword: u.tempPassword });
  } else {
    await admin.post('/users', {
      username: u.username,
      fullName: u.fullName,
      email: `${u.username}@e2e.example.test`,
      password: u.tempPassword,
      assignments: [{ roleCode, siteId }],
    });
  }
  const first = await Api.login(u.username, u.tempPassword!);
  await first.post('/auth/change-password', { currentPassword: u.tempPassword, newPassword: u.password });
  await first.dispose();
}

async function ensureDeviceKey(admin: Api, siteId: string, code: string, name: string, type: 'KIOSK' | 'DISPLAY'): Promise<string> {
  const devices = await admin.get<Device[]>(`/sites/${siteId}/devices`);
  const existing = devices.find((d) => d.code === code);
  const res = existing
    ? await admin.post<{ apiKey: string }>(`/sites/${siteId}/devices/${existing.id}/rotate-key`)
    : await admin.post<{ apiKey: string }>(`/sites/${siteId}/devices`, { code, name, type });
  if (existing) await admin.put(`/sites/${siteId}/devices/${existing.id}`, { name, active: true });
  return res.apiKey;
}

export default async function globalSetup(): Promise<void> {
  await waitForBackend();
  const admin = await superadmin();
  try {
    // Region and site
    const regions = await admin.get<Region[]>('/regions');
    const region = regions.find((r) => r.code === REGION.code) ?? (await admin.post<Region>('/regions', REGION));
    const sites = await admin.get<Site[]>('/sites');
    const site =
      sites.find((s) => s.code === SITE.code) ??
      (await admin.post<Site>('/sites', {
        ...SITE, type: 'LHIO', regionId: region.id, area: 'E2E', timezone: 'Asia/Manila',
      }));

    // Site services from the national catalog
    const catalog = await admin.get<ServiceItem[]>('/service-items');
    const siteServices = await admin.get<SiteService[]>(`/sites/${site.id}/services`);
    const ensureService = async (code: string, prefix: string, sortOrder: number) => {
      const item = catalog.find((i) => i.code === code);
      if (!item) throw new Error(`Service item ${code} missing from the catalog`);
      return (
        siteServices.find((s) => s.serviceCode === code) ??
        (await admin.post<SiteService>(`/sites/${site.id}/services`, {
          serviceItemId: item.id, prefix, active: true, sortOrder, estimatedDurationMinutes: 10,
        }))
      );
    };
    const pmrf = await ensureService('SVC-01', 'A', 1);
    const walkIn = await ensureService('SVC-21', 'W', 2);

    // Counters handling both services
    const counters = await admin.get<Counter[]>(`/sites/${site.id}/counters`);
    const ensureCounter = async (code: string, name: string) =>
      counters.find((c) => c.code === code) ??
      (await admin.post<Counter>(`/sites/${site.id}/counters`, {
        code, name, active: true, siteServiceIds: [pmrf.id, walkIn.id],
      }));
    const c1 = await ensureCounter('C1', 'Counter 1');
    const c2 = await ensureCounter('C2', 'Counter 2');

    // Staff
    await ensureStaffUser(admin, AGENT, 'AGENT', site.id);
    await ensureStaffUser(admin, SUPERVISOR, 'SUPERVISOR', site.id);

    // Devices
    const kioskKey = await ensureDeviceKey(admin, site.id, 'E2E-KIOSK-1', 'E2E kiosk', 'KIOSK');
    const displayKey = await ensureDeviceKey(admin, site.id, 'E2E-DISPLAY-1', 'E2E display', 'DISPLAY');

    const priorities = await admin.get<Priority[]>('/priority-categories');
    const senior = priorities.find((p) => p.code === 'SENIOR');
    if (!senior) throw new Error('Priority category SENIOR missing');

    const state: SeedState = {
      regionId: region.id,
      siteId: site.id,
      siteCode: site.code,
      siteName: site.name,
      services: {
        pmrf: { id: pmrf.id, name: pmrf.serviceName, prefix: pmrf.prefix },
        walkIn: { id: walkIn.id, name: walkIn.serviceName, prefix: walkIn.prefix },
      },
      priorities: { senior: { id: senior.id, name: senior.name } },
      counters: {
        c1: { id: c1.id, code: c1.code, name: c1.name },
        c2: { id: c2.id, code: c2.code, name: c2.name },
      },
      devices: { kioskKey, displayKey },
      users: {
        superadmin: { username: 'superadmin', fullName: 'Super Administrator', password: SUPERADMIN_PASSWORD },
        agent: AGENT,
        supervisor: SUPERVISOR,
      },
    };
    saveState(state);
  } finally {
    await admin.dispose();
  }
}
