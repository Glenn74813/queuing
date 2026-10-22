import { useCallback } from 'react';
import { queueApi } from '../api/agent';
import type { QueueEvent, QueueSnapshot } from '../api/types';
import { tokenStore } from '../auth/tokenStore';
import { useAsync, type AsyncState } from './useAsync';
import { useQueueTopic } from './useQueueTopic';

/** STOMP CONNECT headers for staff clients (bearer token, if the broker checks it). */
export function staffConnectHeaders(): Record<string, string> {
  const token = tokenStore.getAccessToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Live QueueSnapshot for a site: initial fetch + STOMP-triggered refresh + 15 s polling. */
export function useSiteQueue(
  siteId: string | null,
  onEvent?: (e: QueueEvent) => void,
): AsyncState<QueueSnapshot> & { connected: boolean } {
  const state = useAsync(() => queueApi.snapshot(siteId as string), [siteId], !!siteId);
  const { reload } = state;
  const refresh = useCallback(() => void reload(), [reload]);
  const { connected } = useQueueTopic(siteId, { refresh, onEvent, connectHeaders: staffConnectHeaders });
  return { ...state, connected };
}
