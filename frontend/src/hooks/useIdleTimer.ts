import { useEffect, useRef } from 'react';

const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'touchstart', 'wheel'] as const;

/** Calls `onIdle` after `timeoutMs` without user interaction while `active` is true. */
export function useIdleTimer(timeoutMs: number, onIdle: () => void, active = true): void {
  const cb = useRef(onIdle);
  cb.current = onIdle;

  useEffect(() => {
    if (!active) return;
    let timer = window.setTimeout(() => cb.current(), timeoutMs);
    const reset = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => cb.current(), timeoutMs);
    };
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, reset, { passive: true }));
    return () => {
      window.clearTimeout(timer);
      ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, reset));
    };
  }, [timeoutMs, active]);
}
