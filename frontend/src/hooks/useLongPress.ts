import { useCallback, useRef } from 'react';

/** Returns pointer handlers that fire `onLongPress` after the pointer is held for `ms` milliseconds. */
export function useLongPress(onLongPress: () => void, ms: number) {
  const timer = useRef<number | undefined>(undefined);
  const cancel = useCallback(() => {
    window.clearTimeout(timer.current);
    timer.current = undefined;
  }, []);
  const start = useCallback(() => {
    cancel();
    timer.current = window.setTimeout(onLongPress, ms);
  }, [cancel, onLongPress, ms]);
  return {
    onPointerDown: start,
    onPointerUp: cancel,
    onPointerLeave: cancel,
    onPointerCancel: cancel,
    onContextMenu: (e: { preventDefault: () => void }) => e.preventDefault(),
  };
}
