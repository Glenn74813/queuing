import { useId } from 'react';

export interface BarDatum {
  key: string;
  label: string;
  value: number;
  /** Extra detail for the hover/focus tooltip (e.g. "12 served · 1 abandoned"). */
  detail?: string;
}

interface Props {
  title: string;
  data: BarDatum[];
  formatValue?: (v: number) => string;
  /** Label for the value in tooltips and the accessible description, e.g. "clients". */
  unit?: string;
}

/**
 * Horizontal bar chart for ranked categories with long names (one series, so no legend; the title
 * names it). Thin bars from a shared baseline with rounded data ends, the value at each tip, and a
 * tooltip on hover or keyboard focus. Callers should also offer a table view.
 */
export function BarChart({ title, data, formatValue = (v) => v.toLocaleString('en-US'), unit = '' }: Props) {
  const titleId = useId();
  const max = Math.max(1, ...data.map((d) => d.value));
  return (
    <figure className="viz-root viz-bars mb-0" aria-labelledby={titleId}>
      <figcaption id={titleId} className="fw-semibold mb-2">{title}</figcaption>
      <ul className="list-unstyled mb-0">
        {data.map((d) => {
          const text = `${d.label}: ${formatValue(d.value)}${unit ? ` ${unit}` : ''}${d.detail ? ` (${d.detail})` : ''}`;
          return (
            <li key={d.key} className="viz-bar-row" tabIndex={0} aria-label={text}>
              <span className="viz-bar-label">{d.label}</span>
              <span className="viz-bar-track" aria-hidden="true">
                <span className="viz-bar" style={{ width: d.value === 0 ? 0 : `max(2px, ${(d.value / max) * 100}%)` }} />
                <span className="viz-bar-value">{formatValue(d.value)}</span>
              </span>
              <span className="viz-bar-tip" role="tooltip">
                <strong>{formatValue(d.value)}</strong> {unit}
                {d.detail && <span className="viz-muted"> · {d.detail}</span>}
              </span>
            </li>
          );
        })}
      </ul>
    </figure>
  );
}
