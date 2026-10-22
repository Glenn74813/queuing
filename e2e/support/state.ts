import fs from 'node:fs';
import path from 'node:path';
import { STATE_PATH } from './env';

export interface SeedUser {
  username: string;
  fullName: string;
  /** Temporary password set by the administrator (already changed by the global set-up). */
  tempPassword?: string;
  /** Current password. */
  password: string;
}

/** Everything global-setup.ts seeds through the API; persisted to .auth/state.json. */
export interface SeedState {
  regionId: string;
  siteId: string;
  siteCode: string;
  siteName: string;
  services: {
    pmrf: { id: string; name: string; prefix: string };
    walkIn: { id: string; name: string; prefix: string };
  };
  priorities: { senior: { id: string; name: string } };
  counters: {
    c1: { id: string; code: string; name: string };
    c2: { id: string; code: string; name: string };
  };
  devices: { kioskKey: string; displayKey: string };
  users: { superadmin: SeedUser; agent: SeedUser; supervisor: SeedUser };
}

let cached: SeedState | null = null;

export function loadState(): SeedState {
  if (!cached) cached = JSON.parse(fs.readFileSync(STATE_PATH, 'utf8')) as SeedState;
  return cached;
}

export function saveState(state: SeedState): void {
  fs.mkdirSync(path.dirname(STATE_PATH), { recursive: true });
  fs.writeFileSync(STATE_PATH, JSON.stringify(state, null, 2), { mode: 0o600 });
}
