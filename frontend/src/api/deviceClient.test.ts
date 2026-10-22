import type { InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEVICE_KEY_REJECTED_EVENT,
  LEGACY_DEVICE_KEY_STORAGE,
  deviceApi,
  deviceKeyStorage,
  getDeviceKey,
  roleForPath,
  setDeviceKey,
} from './deviceClient';

describe('device keys', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('keeps kiosk and display keys separate in one browser', () => {
    setDeviceKey('kiosk', 'qdk_kiosk');
    setDeviceKey('display', 'qdk_display');
    expect(getDeviceKey('kiosk')).toBe('qdk_kiosk');
    expect(getDeviceKey('display')).toBe('qdk_display');
  });

  it('falls back to the legacy shared slot until a role has its own key', () => {
    window.localStorage.setItem(LEGACY_DEVICE_KEY_STORAGE, 'qdk_old');
    expect(getDeviceKey('display')).toBe('qdk_old');
    setDeviceKey('kiosk', 'qdk_kiosk');
    expect(window.localStorage.getItem(LEGACY_DEVICE_KEY_STORAGE)).toBeNull();
    expect(getDeviceKey('display')).toBeNull();
  });

  it('maps API paths to the key they need', () => {
    expect(roleForPath('/kiosk/config')).toBe('kiosk');
    expect(roleForPath('/kiosk/tickets')).toBe('kiosk');
    expect(roleForPath('/display/board')).toBe('display');
    expect(roleForPath('http://localhost:8080/api/v1/display/board')).toBe('display');
    expect(roleForPath('/auth/me')).toBeNull();
  });

  it('sends the key of the matching role', async () => {
    setDeviceKey('kiosk', 'qdk_kiosk');
    setDeviceKey('display', 'qdk_display');
    const seen: Record<string, string | undefined> = {};
    const adapter = async (config: InternalAxiosRequestConfig) => {
      seen[String(config.url)] = String(config.headers?.['X-Device-Key']);
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config };
    };
    await deviceApi.get('/kiosk/config', { adapter: adapter as never });
    await deviceApi.get('/display/board', { adapter: adapter as never });
    expect(seen['/kiosk/config']).toBe('qdk_kiosk');
    expect(seen['/display/board']).toBe('qdk_display');
  });

  it('clears a rejected key and announces it, instead of retrying forever', async () => {
    setDeviceKey('display', 'qdk_rotated');
    setDeviceKey('kiosk', 'qdk_kiosk');
    const events: string[] = [];
    const listener = (e: Event) => events.push((e as CustomEvent<{ role: string }>).detail.role);
    window.addEventListener(DEVICE_KEY_REJECTED_EVENT, listener);
    const reject401 = async (config: never) => {
      const err = Object.assign(new Error('401'), { config, response: { status: 401, data: {} } });
      throw err;
    };
    await expect(deviceApi.get('/display/board', { adapter: reject401 as never })).rejects.toThrow();
    window.removeEventListener(DEVICE_KEY_REJECTED_EVENT, listener);
    expect(events).toEqual(['display']);
    expect(window.localStorage.getItem(deviceKeyStorage('display'))).toBeNull();
    expect(getDeviceKey('kiosk')).toBe('qdk_kiosk');   // the other device's key is untouched
  });
});
