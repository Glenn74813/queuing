import type { Counter, CounterHours } from '../api/types';

export const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const hm = (t: string) => t.slice(0, 5);
const minutes = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5)) + (t.length >= 8 ? Number(t.slice(6, 8)) / 60 : 0);

export function sortHours(hours: CounterHours[]): CounterHours[] {
  return hours.slice().sort((a, b) => a.dayOfWeek - b.dayOfWeek || minutes(a.openTime) - minutes(b.openTime));
}

/**
 * Compact weekly summary; consecutive days with the same windows are grouped, e.g.
 * "Mon–Fri 08:00–12:00, 13:00–17:00 · Sat 08:00–12:00". Empty = "Always (no schedule)".
 */
export function formatHours(hours: CounterHours[]): string {
  if (hours.length === 0) return 'Always (no schedule)';
  const byDay = new Map<number, string>();
  for (const h of sortHours(hours)) {
    const w = `${hm(h.openTime)}–${hm(h.closeTime)}`;
    byDay.set(h.dayOfWeek, byDay.has(h.dayOfWeek) ? `${byDay.get(h.dayOfWeek)}, ${w}` : w);
  }
  const parts: string[] = [];
  let start = 0;
  const days = [1, 2, 3, 4, 5, 6, 7];
  while (start < days.length) {
    const windows = byDay.get(days[start]);
    let end = start;
    while (end + 1 < days.length && windows && byDay.get(days[end + 1]) === windows) end++;
    if (windows) {
      const label = end > start ? `${DAY_NAMES[start]}–${DAY_NAMES[end]}` : DAY_NAMES[start];
      parts.push(`${label} ${windows}`);
    }
    start = end + 1;
  }
  return parts.join(' · ');
}

/** Same rules as the server: closing after opening, no overlapping windows on a day. Null when valid. */
export function hoursError(hours: CounterHours[]): string | null {
  const sorted = sortHours(hours);
  for (let i = 0; i < sorted.length; i++) {
    const h = sorted[i];
    if (!h.openTime || !h.closeTime) return `${DAY_NAMES[h.dayOfWeek - 1]}: enter both opening and closing time.`;
    if (minutes(h.closeTime) <= minutes(h.openTime)) return `${DAY_NAMES[h.dayOfWeek - 1]}: closing must be after opening.`;
    const prev = sorted[i - 1];
    if (prev && prev.dayOfWeek === h.dayOfWeek && minutes(prev.closeTime) > minutes(h.openTime)) {
      return `${DAY_NAMES[h.dayOfWeek - 1]}: opening windows overlap.`;
    }
  }
  return null;
}

export type CounterSituation = { label: string; tone: 'success' | 'warning' | 'secondary' | 'info' };

/** What a supervisor needs to know about a counter right now, from its schedule and staffing. */
export function counterSituation(c: Pick<Counter, 'active' | 'currentAgent' | 'scheduleState'>): CounterSituation {
  if (!c.active) return { label: 'Inactive', tone: 'secondary' };
  const staffed = !!c.currentAgent;
  if (c.scheduleState === 'OPEN') return staffed ? { label: 'Open', tone: 'success' } : { label: 'Scheduled open, no agent', tone: 'warning' };
  if (c.scheduleState === 'CLOSED') return staffed ? { label: 'Staffed outside hours', tone: 'info' } : { label: 'Closed (schedule)', tone: 'secondary' };
  return staffed ? { label: 'Open', tone: 'success' } : { label: 'No agent', tone: 'secondary' };
}
