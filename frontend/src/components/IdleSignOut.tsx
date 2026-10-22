import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useIdleTimer } from '../hooks/useIdleTimer';
import { Modal } from './Modal';

/** Seconds of warning shown before an idle sign-out. */
export const IDLE_WARNING_SECONDS = 60;

/**
 * Signs the user out after the server-configured period without keyboard, mouse or touch activity
 * (Me.idleTimeoutMinutes; 0 disables it), with a one-minute warning that offers to stay signed in.
 */
export function IdleSignOut() {
  const { me, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const minutes = me?.idleTimeoutMinutes ?? 0;
  const [left, setLeft] = useState<number | null>(null);
  const signedOut = useRef(false);
  const warnAfterMs = Math.max(0, minutes * 60_000 - IDLE_WARNING_SECONDS * 1000);

  useIdleTimer(warnAfterMs, () => setLeft(IDLE_WARNING_SECONDS), minutes > 0 && left === null);

  useEffect(() => {
    if (left === null) return;
    if (left <= 0) {
      if (signedOut.current) return;
      signedOut.current = true;
      void logout().then(() =>
        navigate('/login', { replace: true, state: { from: location.pathname, reason: 'idle' } }),
      );
      return;
    }
    const t = window.setTimeout(() => setLeft((s) => (s === null ? null : s - 1)), 1000);
    return () => window.clearTimeout(t);
  }, [left, logout, navigate, location.pathname]);

  if (left === null || left <= 0) return null;
  return (
    <Modal title="Are you still there?" onClose={() => setLeft(null)}
      footer={<button type="button" className="btn btn-primary" onClick={() => setLeft(null)}>Stay signed in</button>}>
      <p className="mb-0" role="timer" aria-live="polite">
        For security, you will be signed out in <strong>{left}</strong> second{left === 1 ? '' : 's'} because there has
        been no activity for {minutes} minutes.
      </p>
    </Modal>
  );
}
