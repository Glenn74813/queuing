import { Client, type IMessage } from '@stomp/stompjs';
import { useEffect, useRef, useState } from 'react';
import type { QueueEvent } from '../api/types';
import { brokerUrlFromLocation } from './wsUrl';

export const QUEUE_POLL_INTERVAL_MS = 15_000;

interface Options {
  /** Called on every queue event (after JSON parse). */
  onEvent?: (event: QueueEvent) => void;
  /**
   * Re-fetch callback: invoked on QUEUE_CHANGED / TICKET_* events, after every (re)connect
   * and on the polling fallback timer.
   */
  refresh: () => void;
  /** Extra STOMP CONNECT headers (e.g. Authorization or X-Device-Key). Read on each (re)connect. */
  connectHeaders?: () => Record<string, string>;
  pollMs?: number;
}

/**
 * Subscribes to /topic/sites/{siteId}/queue over STOMP (native WebSocket) with automatic reconnect,
 * plus a polling fallback so the UI recovers even when the socket is blocked.
 * Returns whether the socket is currently connected (for a status indicator).
 */
export function useQueueTopic(siteId: string | null | undefined, options: Options): { connected: boolean } {
  const [connected, setConnected] = useState(false);
  const optsRef = useRef(options);
  optsRef.current = options;

  // STOMP subscription with auto-reconnect.
  useEffect(() => {
    if (!siteId) return;
    const client = new Client({
      brokerURL: brokerUrlFromLocation(),
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      debug: () => undefined,
      beforeConnect: () => {
        client.connectHeaders = optsRef.current.connectHeaders?.() ?? {};
      },
      onConnect: () => {
        setConnected(true);
        client.subscribe(`/topic/sites/${siteId}/queue`, (msg: IMessage) => {
          let event: QueueEvent | null = null;
          try {
            event = JSON.parse(msg.body) as QueueEvent;
          } catch {
            /* malformed payload: still refresh below */
          }
          if (event) optsRef.current.onEvent?.(event);
          optsRef.current.refresh();
        });
        // Catch up on anything missed while disconnected.
        optsRef.current.refresh();
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    });
    client.activate();
    return () => {
      setConnected(false);
      void client.deactivate();
    };
  }, [siteId]);

  // Polling fallback (also covers missed messages).
  useEffect(() => {
    if (!siteId) return;
    const timer = window.setInterval(() => optsRef.current.refresh(), options.pollMs ?? QUEUE_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [siteId, options.pollMs]);

  return { connected };
}
