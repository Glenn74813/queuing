/** Small connection-state indicator for live (WebSocket) pages; polling continues when offline. */
export function LiveIndicator({ connected }: { connected: boolean }) {
  return (
    <span className={`badge ${connected ? 'text-bg-success' : 'text-bg-secondary'}`} role="status">
      {connected ? 'Live' : 'Polling (reconnecting…)'}
    </span>
  );
}
