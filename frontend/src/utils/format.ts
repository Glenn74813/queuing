/** Display helpers. All API timestamps are ISO-8601 UTC; they are rendered in the browser's locale/time zone. */

const EMPTY = '—';

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? EMPTY : d.toLocaleString();
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? EMPTY : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Business date "2026-10-23" as "23 Oct 2026" (no time-zone conversion: it is already a local date). */
export function formatBusinessDate(date: string | null | undefined): string {
  const m = date ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(date) : null;
  return m ? `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]} ${m[1]}` : EMPTY;
}

/** A from/to pair of business dates, shortened when they share a month or year: "16–23 Oct 2026". */
export function formatDateRange(from: string, to: string): string {
  if (from === to) return formatBusinessDate(from);
  const [fy, fm] = from.split('-');
  const [ty, tm] = to.split('-');
  const a = formatBusinessDate(from).split(' ');
  if (fy === ty && fm === tm) return `${a[0]}–${formatBusinessDate(to)}`;
  if (fy === ty) return `${a[0]} ${a[1]} – ${formatBusinessDate(to)}`;
  return `${formatBusinessDate(from)} – ${formatBusinessDate(to)}`;
}

/** Today's date as yyyy-MM-dd in local time (for date inputs). */
export function todayIso(offsetDays = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Converts a datetime-local input value to an ISO instant (or undefined when blank). */
export function localInputToIso(value: string): string | undefined {
  if (!value) return undefined;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

/** Converts an ISO instant to a datetime-local input value. */
export function isoToLocalInput(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function enumLabel(value: string | null | undefined): string {
  if (!value) return EMPTY;
  return value
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}
