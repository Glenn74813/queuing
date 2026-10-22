import path from 'node:path';

/** Frontend (Vite dev server or any deployment serving the SPA). */
export const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5180';
/** Backend REST base, used for seeding and test set-up. */
export const API_URL = (process.env.E2E_API_URL ?? 'http://localhost:8095/api/v1').replace(/\/$/, '');
/** Password the backend was started with in QMS_BOOTSTRAP_ADMIN_PASSWORD (first login only). */
export const BOOTSTRAP_PASSWORD = process.env.E2E_BOOTSTRAP_PASSWORD ?? 'E2e-Bootstrap-Pass-1';
/** Password the global set-up gives the bootstrap superadmin. */
export const SUPERADMIN_PASSWORD = process.env.E2E_SUPERADMIN_PASSWORD ?? 'E2e-Root-Admin-Pass-9';
/** Seeded data (ids, device keys, passwords) written by global-setup.ts. */
export const STATE_PATH = path.resolve(__dirname, '..', '.auth', 'state.json');
