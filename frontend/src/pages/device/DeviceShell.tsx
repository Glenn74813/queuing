import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { toApiError } from '../../api/client';
import { DEVICE_KEY_REJECTED_EVENT, getDeviceKey, setDeviceKey, type DeviceRole } from '../../api/deviceClient';
import type { ApiErrorBody } from '../../api/types';
import { AgencyBrand } from '../../components/AgencyBrand';
import { ErrorAlert } from '../../components/ErrorAlert';
import { useLongPress } from '../../hooks/useLongPress';

/** Hold the top-left corner this long to reach the "reset device" prompt. */
export const RESET_HOLD_MS = 5000;

interface Props {
  kind: 'Kiosk' | 'Queue display';
  /** Called with the entered key to verify it (e.g. GET /kiosk/config). Should throw on failure. */
  verify: () => Promise<unknown>;
  children: ReactNode;
}

/**
 * Wraps unauthenticated device pages: shows a one-time setup screen until a device key is stored
 * in localStorage ('qms.deviceKey.kiosk' or 'qms.deviceKey.display'), and provides the hidden reset gesture (long-press top-left 5 s → confirm).
 */
export function DeviceShell({ kind, verify, children }: Props) {
  const role: DeviceRole = kind === 'Kiosk' ? 'kiosk' : 'display';
  const [hasKey, setHasKey] = useState(() => !!getDeviceKey(role));
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);

  // The server rejected the stored key while running (rotated, deactivated, or a key of the other
  // device type): go back to setup and say why, rather than showing "reconnecting" indefinitely.
  useEffect(() => {
    const onRejected = (e: Event) => {
      if ((e as CustomEvent<{ role: DeviceRole }>).detail?.role !== role) return;
      setHasKey(false);
      setError({
        status: 401,
        code: 'UNAUTHORIZED',
        message: `The saved device key is no longer accepted. It may have been rotated or deactivated, or it belongs to a ${role === 'kiosk' ? 'queue display' : 'kiosk'}. Enter this ${kind.toLowerCase()}'s current key.`,
      } as ApiErrorBody);
    };
    window.addEventListener(DEVICE_KEY_REJECTED_EVENT, onRejected);
    return () => window.removeEventListener(DEVICE_KEY_REJECTED_EVENT, onRejected);
  }, [role, kind]);

  const onReset = useCallback(() => {
    if (window.confirm(`Reset this ${kind.toLowerCase()}? The device key will be removed and must be entered again.`)) {
      setDeviceKey(role, null);
      window.location.reload();
    }
  }, [kind, role]);
  const longPress = useLongPress(onReset, RESET_HOLD_MS);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setDeviceKey(role, key.trim());
    try {
      await verify();
      setHasKey(true);
    } catch (err) {
      setDeviceKey(role, null);
      const apiErr = toApiError(err);
      setError(
        apiErr.status === 401 || apiErr.status === 403
          ? { ...apiErr, message: 'The key was not accepted. Check that it belongs to an active device of this type.' }
          : apiErr,
      );
    } finally {
      setBusy(false);
    }
  };

  if (!hasKey) {
    return (
      <main className="min-vh-100 d-flex align-items-center justify-content-center bg-body-tertiary p-3">
        <form className="card shadow-sm p-4" style={{ maxWidth: 520, width: '100%' }} onSubmit={submit}>
          <div className="text-primary mb-3"><AgencyBrand /></div>
          <h1 className="h4">{kind} setup</h1>
          <p className="text-body-secondary">
            Enter the device API key issued by an administrator (Admin › Devices). It is stored on this device only.
          </p>
          <ErrorAlert error={error} />
          <label htmlFor="device-key" className="form-label">Device API key</label>
          <input id="device-key" className="form-control font-monospace mb-3" autoComplete="off" required value={key}
            onChange={(e) => setKey(e.target.value)} />
          <button type="submit" className="btn btn-primary btn-lg" disabled={busy || !key.trim()}>
            {busy ? 'Checking…' : 'Save and start'}
          </button>
        </form>
      </main>
    );
  }

  return (
    <>
      {/* Invisible staff-only reset hotspot; deliberately not focusable to avoid accidental activation. */}
      <div className="kiosk-reset-zone" aria-hidden="true" {...longPress} />
      {children}
    </>
  );
}

/** Content currently eligible to show: PUBLISHED and inside its optional start/end window. */
export function isContentLive(c: { status: string; startsAt?: string; endsAt?: string }, now = new Date()): boolean {
  if (c.status !== 'PUBLISHED') return false;
  if (c.startsAt && new Date(c.startsAt) > now) return false;
  if (c.endsAt && new Date(c.endsAt) <= now) return false;
  return true;
}
