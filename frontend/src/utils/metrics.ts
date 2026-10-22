/**
 * Formatting helpers for report metrics. Null means "no data" (e.g. no served tickets) and must render
 * as a dash rather than 0 so averages are not misread.
 */

export const NO_DATA = '—';

/** Average minutes with one decimal, e.g. 12.345 → "12.3". */
export function formatMinutes(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return NO_DATA;
  return value.toFixed(1);
}

/** Duration in seconds as h:mm:ss or m:ss. */
export function formatSeconds(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value) || value < 0) return NO_DATA;
  const total = Math.round(value);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

/** Average rating out of 5 with two decimals. */
export function formatRating(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return NO_DATA;
  return value.toFixed(2);
}

/** Integer counts with thousands separators. */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return NO_DATA;
  return value.toLocaleString('en-US');
}

/** served / issued as a percentage with one decimal; dash when nothing was issued. */
export function formatServedRate(served: number, issued: number): string {
  if (!issued) return NO_DATA;
  return `${((served / issued) * 100).toFixed(1)}%`;
}

/** Short explanations shown next to the summary table (full text lives in docs/metrics-definitions.md). */
export const METRIC_DEFINITIONS: { term: string; definition: string }[] = [
  { term: 'Issued', definition: 'Tickets issued in the period (kiosk printed + electronic).' },
  { term: 'Served', definition: 'Tickets that reached COMPLETED.' },
  { term: 'Abandoned', definition: 'Tickets removed (e.g. no-show) or expired at end of day without service.' },
  { term: 'Voided', definition: 'Tickets cancelled by a supervisor (e.g. issued in error).' },
  { term: 'Rerouted', definition: 'Tickets transferred to another service at least once.' },
  { term: 'Queue time', definition: 'From ticket issued to the first call at a counter.' },
  { term: 'Wait time', definition: 'Total time spent WAITING across all stages (includes waits after transfers).' },
  { term: 'Process time', definition: 'Total time the ticket was CALLED/being served at counters.' },
  { term: 'Feedback / Rating', definition: 'Number of feedback submissions and the average rating (1–5).' },
];
