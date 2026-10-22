import axios, { type AxiosInstance } from 'axios';
import { API_BASE_URL } from './client';

/** Kiosks and queue displays keep separate keys, so one browser can run both pages. */
export type DeviceRole = 'kiosk' | 'display';

/** Pre-2026-10 single shared slot; still read as a fallback so existing devices keep working. */
export const LEGACY_DEVICE_KEY_STORAGE = 'qms.deviceKey';

/** Fired on window when the server rejects a stored key (rotated, deactivated or wrong device type). */
export const DEVICE_KEY_REJECTED_EVENT = 'qms:device-key-rejected';

export function deviceKeyStorage(role: DeviceRole): string {
  return `${LEGACY_DEVICE_KEY_STORAGE}.${role}`;
}

export function getDeviceKey(role: DeviceRole): string | null {
  try {
    return window.localStorage.getItem(deviceKeyStorage(role)) ?? window.localStorage.getItem(LEGACY_DEVICE_KEY_STORAGE);
  } catch {
    return null;
  }
}

export function setDeviceKey(role: DeviceRole, key: string | null): void {
  try {
    if (key) window.localStorage.setItem(deviceKeyStorage(role), key);
    else window.localStorage.removeItem(deviceKeyStorage(role));
    // The shared legacy slot may hold the other device type's key; once a role has its own, drop it.
    window.localStorage.removeItem(LEGACY_DEVICE_KEY_STORAGE);
  } catch {
    /* storage unavailable: device will ask again */
  }
}

/** Which device key an API path needs: /kiosk/* uses the kiosk key, /display/* the display key. */
export function roleForPath(url: string | undefined): DeviceRole | null {
  const path = (url ?? '').replace(/^https?:\/\/[^/]+/, '').replace(/^\/api\/v1/, '');
  if (path.startsWith('/kiosk')) return 'kiosk';
  if (path.startsWith('/display')) return 'display';
  return null;
}

/** Axios instance for unauthenticated device endpoints; adds the right X-Device-Key on every request. */
export const deviceApi: AxiosInstance = axios.create({ baseURL: API_BASE_URL, timeout: 15000 });

deviceApi.interceptors.request.use((config) => {
  const role = roleForPath(config.url);
  const key = role ? getDeviceKey(role) : null;
  if (key) config.headers.set('X-Device-Key', key);
  return config;
});

// A key the server no longer accepts will never start working again: clear it and let the page
// return to its setup screen instead of retrying forever as if the network were down.
deviceApi.interceptors.response.use(undefined, (error) => {
  const status: number | undefined = error?.response?.status;
  const role = roleForPath(error?.config?.url);
  if (role && (status === 401 || status === 403)) {
    setDeviceKey(role, null);
    window.dispatchEvent(new CustomEvent(DEVICE_KEY_REJECTED_EVENT, { detail: { role } }));
  }
  return Promise.reject(error);
});
