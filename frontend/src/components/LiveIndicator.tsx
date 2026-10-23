/** Small connection-state indicator for live (WebSocket) pages; polling continues when offline. */
export function LiveIndicator({ connected }: { connected: boolean }) {
  return (
    <span className={`live-pill ${connected ? 'is-live' : ''}`} role="status">
      <span className="live-dot" aria-hidden="true" />
      {connected ? 'Live' : 'Polling (reconnecting…)'}
    </span>
  );
}
