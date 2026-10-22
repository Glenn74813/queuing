import { useId, useState } from 'react';

export interface PieSlice {
  key: string;
  label: string;
  value: number;
  /** Extra detail for the tooltip, e.g. "123 served · 14 abandoned". */
  detail?: string;
  /** Neutral "Other" slice (folded small categories): drawn grey, never a series hue. */
  other?: boolean;
}

interface Props {
  title: string;
  slices: PieSlice[];
  unit?: string;
}

/** Categorical slots 1-5 (validated as a set, including the wrap-around pair) plus neutral "Other". */
const MAX_SLICES = 6;

const pct = (v: number, total: number) => (total === 0 ? 0 : (v / total) * 100);
const fmtPct = (p: number) => (p > 0 && p < 1 ? '<1%' : `${Math.round(p)}%`);

/** SVG path of one slice from angle a0 to a1 (radians, 0 = 12 o'clock, clockwise). */
function slicePath(cx: number, cy: number, r: number, a0: number, a1: number): string {
  const x0 = cx + r * Math.sin(a0);
  const y0 = cy - r * Math.cos(a0);
  const x1 = cx + r * Math.sin(a1);
  const y1 = cy - r * Math.cos(a1);
  const large = a1 - a0 > Math.PI ? 1 : 0;
  return `M${cx},${cy} L${x0.toFixed(2)},${y0.toFixed(2)} A${r},${r} 0 ${large} 1 ${x1.toFixed(2)},${y1.toFixed(2)} Z`;
}

/**
 * Pie chart for part-to-whole at a glance, capped at {@link MAX_SLICES} slices (callers fold the rest
 * into an "Other" slice). Slices are separated by a 2px surface gap; a legend carries every label,
 * value and share (so identity never relies on colour), and hovering or focusing a slice or legend
 * entry shows its detail. Callers should also offer a table view.
 */
export function PieChart({ title, slices, unit = '' }: Props) {
  const titleId = useId();
  const [active, setActive] = useState<string | null>(null);
  const shown = slices.filter((s) => s.value > 0).slice(0, MAX_SLICES);
  const total = shown.reduce((n, s) => n + s.value, 0);
  let series = 0;
  const colored = shown.map((s) => ({ ...s, cls: s.other ? 'viz-fill-other' : `viz-fill-${(series++ % 5) + 1}` }));

  const cx = 110;
  const cy = 110;
  const r = 100;
  let angle = 0;
  const paths = colored.map((s) => {
    const sweep = (s.value / total) * Math.PI * 2;
    const p = { ...s, a0: angle, a1: angle + sweep };
    angle += sweep;
    return p;
  });
  const current = colored.find((s) => s.key === active);

  return (
    <figure className="viz-root viz-pie mb-0" aria-labelledby={titleId}>
      <figcaption id={titleId} className="fw-semibold mb-2">{title}</figcaption>
      {total === 0 ? (
        <p className="text-body-secondary mb-0">No clients in this period.</p>
      ) : (
        <div className="viz-pie-body">
          <div className="viz-pie-plot">
            <svg viewBox="0 0 220 220" role="img" aria-label={`${title}: ${colored.map((s) => `${s.label} ${fmtPct(pct(s.value, total))}`).join(', ')}`}>
              {paths.length === 1 ? (
                <circle cx={cx} cy={cy} r={r} className={`${paths[0].cls} viz-slice`}
                  onPointerEnter={() => setActive(paths[0].key)} onPointerLeave={() => setActive(null)} />
              ) : (
                paths.map((s) => (
                  <path key={s.key} d={slicePath(cx, cy, r, s.a0, s.a1)}
                    className={`${s.cls} viz-slice${active && active !== s.key ? ' viz-dim' : ''}`}
                    onPointerEnter={() => setActive(s.key)} onPointerLeave={() => setActive(null)} />
                ))
              )}
            </svg>
            {current && (
              <div className="viz-tooltip small shadow-sm viz-pie-tip" role="status">
                <div className="viz-tooltip-date">{current.label}</div>
                <strong>{current.value.toLocaleString('en-US')}</strong> {unit} · {fmtPct(pct(current.value, total))}
                {current.detail && <div className="viz-muted">{current.detail}</div>}
              </div>
            )}
          </div>
          <ul className="viz-pie-legend list-unstyled mb-0">
            {colored.map((s) => (
              <li key={s.key} tabIndex={0} className={active === s.key ? 'active' : undefined}
                onPointerEnter={() => setActive(s.key)} onPointerLeave={() => setActive(null)}
                onFocus={() => setActive(s.key)} onBlur={() => setActive(null)}
                aria-label={`${s.label}: ${s.value} ${unit}, ${fmtPct(pct(s.value, total))}${s.detail ? ` (${s.detail})` : ''}`}>
                <svg width="12" height="12" aria-hidden="true"><rect width="12" height="12" rx="2" className={s.cls} /></svg>
                <span className="viz-pie-label">{s.label}</span>
                <span className="viz-pie-value">{s.value.toLocaleString('en-US')}</span>
                <span className="viz-pie-pct">{fmtPct(pct(s.value, total))}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </figure>
  );
}
