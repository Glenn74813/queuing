import type { SummaryRow } from '../api/types';

/** One business day of the trend; null averages mean "no ticket had the measurement". */
export interface TrendPoint {
  date: string;
  issued: number;
  served: number;
  abandoned: number;
  avgWaitMinutes: number | null;
}

const DAY_MS = 86_400_000;

/** Every yyyy-MM-dd from `from` to `to` inclusive (UTC arithmetic, so no DST surprises). */
export function daysBetween(from: string, to: string): string[] {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return [];
  const out: string[] = [];
  for (let t = start; t <= end; t += DAY_MS) out.push(new Date(t).toISOString().slice(0, 10));
  return out;
}

/**
 * Turns DAY-grouped summary rows into a continuous series: days without tickets become zero
 * counts (nothing happened) but null averages (nothing to average), so lines break there.
 */
export function fillDays(rows: SummaryRow[], from: string, to: string): TrendPoint[] {
  const byDay = new Map(rows.map((r) => [r.key, r]));
  return daysBetween(from, to).map((date) => {
    const r = byDay.get(date);
    return {
      date,
      issued: r?.issued ?? 0,
      served: r?.served ?? 0,
      abandoned: r?.abandoned ?? 0,
      avgWaitMinutes: r?.avgWaitMinutes ?? null,
    };
  });
}

/** Clean axis ticks (1/2/5 × 10^n steps) from 0 up to at least `max`. */
export function niceTicks(max: number, target = 4): number[] {
  if (!(max > 0)) return [0, 1];
  const raw = max / target;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= raw) ?? 10 * pow;
  const ticks: number[] = [];
  for (let v = 0; v < max + step; v += step) {
    ticks.push(Number(v.toPrecision(12)));
    if (v >= max) break;
  }
  return ticks;
}

/** Compact tick label: 0 / 950 / 1.2K / 15K. */
export function compactNumber(v: number): string {
  if (Math.abs(v) >= 1000) return `${Number((v / 1000).toFixed(v % 1000 === 0 ? 0 : 1))}K`;
  return Number.isInteger(v) ? String(v) : v.toFixed(1);
}

/** "Oct 22" style label for a yyyy-MM-dd business date. */
export function shortDate(date: string): string {
  const d = new Date(`${date}T00:00:00Z`);
  return d.toLocaleDateString('en-PH', { month: 'short', day: 'numeric', timeZone: 'UTC' });
}
