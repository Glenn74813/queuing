import { useCallback, useEffect, useRef, useState } from 'react';
import { toApiError } from '../api/client';
import type { ApiErrorBody } from '../api/types';

export interface AsyncState<T> {
  data: T | undefined;
  error: ApiErrorBody | null;
  loading: boolean;
  /** Re-runs the loader; resolves when done. Silent reloads keep the old data visible. */
  reload: () => Promise<void>;
  setData: (d: T | undefined) => void;
}

/**
 * Runs an async loader whenever `deps` change; ignores results from stale runs.
 * Pass `enabled=false` to skip loading (e.g. while a site is not selected yet).
 */
export function useAsync<T>(loader: () => Promise<T>, deps: unknown[], enabled = true): AsyncState<T> {
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<ApiErrorBody | null>(null);
  const [loading, setLoading] = useState<boolean>(enabled);
  const runId = useRef(0);
  const loaderRef = useRef(loader);
  loaderRef.current = loader;

  const reload = useCallback(async () => {
    const id = ++runId.current;
    setLoading(true);
    try {
      const result = await loaderRef.current();
      if (id === runId.current) {
        setData(result);
        setError(null);
      }
    } catch (e) {
      if (id === runId.current) setError(toApiError(e));
    } finally {
      if (id === runId.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return;
    }
    void reload();
  }, [enabled, reload, ...deps]);

  return { data, error, loading, reload, setData };
}
