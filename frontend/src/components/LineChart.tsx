import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { compactNumber, niceTicks, shortDate } from '../utils/trend';

export interface LineSeries {
  key: string;
  label: string;
  /** Categorical slot 1-3; colours come from the --viz-series-N tokens in styles.css. */
  slot: 1 | 2 | 3;
  values: (number | null)[];
}

interface Props {
  title: string;
  /** yyyy-MM-dd per x position. */
  dates: string[];
  series: LineSeries[];
  formatValue: (v: number) => string;
  height?: number;
}

const M = { top: 12, right: 112, bottom: 28, left: 44 };
const LABEL_GAP = 14;

/**
 * Accessible SVG line chart: single y-axis from zero, hairline grid, 2px lines, end-point direct
 * labels, a crosshair tooltip that lists every series (pointer and keyboard), and a legend for 2+
 * series. Callers must also offer a table view of the same numbers.
 */
export function LineChart({ title, dates, series, formatValue, height = 240 }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  const [hover, setHover] = useState<number | null>(null);
  const titleId = useId();

  useEffect(() => {
    const el = wrapRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(([entry]) => setWidth(Math.max(320, Math.floor(entry.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const n = dates.length;
  const plotW = width - M.left - M.right;
  const plotH = height - M.top - M.bottom;
  const max = Math.max(0, ...series.flatMap((s) => s.values.filter((v): v is number => v !== null)));
  const ticks = useMemo(() => niceTicks(max), [max]);
  const yMax = ticks[ticks.length - 1] || 1;
  const x = (i: number) => M.left + (n <= 1 ? plotW / 2 : (i * plotW) / (n - 1));
  const y = (v: number) => M.top + plotH - (v / yMax) * plotH;

  /** Path with gaps at nulls (a day with nothing to average is not drawn as zero). */
  const path = (values: (number | null)[]) => {
    let d = '';
    let pen = false;
    values.forEach((v, i) => {
      if (v === null) {
        pen = false;
        return;
      }
      d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`;
      pen = true;
    });
    return d;
  };

  // End labels at each series' last value, nudged apart so they never overlap.
  const endLabels = series
    .map((s) => {
      const i = s.values.map((v, idx) => (v === null ? -1 : idx)).filter((idx) => idx >= 0).pop();
      return i === undefined ? null : { s, i, v: s.values[i] as number, ly: y(s.values[i] as number) };
    })
    .filter((l): l is NonNullable<typeof l> => l !== null)
    .sort((a, b) => a.ly - b.ly);
  for (let k = 1; k < endLabels.length; k++) {
    if (endLabels[k].ly - endLabels[k - 1].ly < LABEL_GAP) endLabels[k].ly = endLabels[k - 1].ly + LABEL_GAP;
  }
  // Keep the stack inside the plot: if the lowest label overflows, push the stack back up.
  const bottom = M.top + plotH - LABEL_GAP / 2;
  for (let k = endLabels.length - 1; k >= 0; k--) {
    const limit = k === endLabels.length - 1 ? bottom : endLabels[k + 1].ly - LABEL_GAP;
    if (endLabels[k].ly > limit) endLabels[k].ly = limit;
  }

  const xLabelEvery = Math.max(1, Math.ceil(n / Math.max(2, Math.floor(plotW / 70))));
  const indexAt = (clientX: number, rect: DOMRect) => {
    const px = ((clientX - rect.left) / rect.width) * width;
    if (n <= 1) return 0;
    return Math.min(n - 1, Math.max(0, Math.round(((px - M.left) / plotW) * (n - 1))));
  };
  const onMove = (e: PointerEvent<SVGRectElement>) =>
    setHover(indexAt(e.clientX, (e.currentTarget.ownerSVGElement as SVGSVGElement).getBoundingClientRect()));
  const onKey = (e: KeyboardEvent<SVGSVGElement>) => {
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      const step = e.key === 'ArrowRight' ? 1 : -1;
      setHover((h) => Math.min(n - 1, Math.max(0, (h ?? (step > 0 ? -1 : n)) + step)));
    } else if (e.key === 'Escape') {
      setHover(null);
    }
  };

  const tipLeft = hover !== null && x(hover) > width / 2;

  return (
    <figure className="viz-root mb-4" aria-labelledby={titleId}>
      <figcaption id={titleId} className="fw-semibold mb-1">{title}</figcaption>
      {series.length > 1 && (
        <ul className="viz-legend list-unstyled d-flex flex-wrap gap-3 mb-1 small">
          {series.map((s) => (
            <li key={s.key} className="d-flex align-items-center gap-1">
              <svg width="16" height="8" aria-hidden="true"><line x1="0" y1="4" x2="16" y2="4" className={`viz-stroke-${s.slot}`} strokeWidth="2" /></svg>
              {s.label}
            </li>
          ))}
        </ul>
      )}
      <div ref={wrapRef} className="position-relative">
        <svg width="100%" viewBox={`0 0 ${width} ${height}`} role="img" tabIndex={0} className="viz-svg"
          aria-label={`${title}. Use the left and right arrow keys to read each day.`}
          onKeyDown={onKey} onBlur={() => setHover(null)}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={M.left} x2={M.left + plotW} y1={y(t)} y2={y(t)} className={t === 0 ? 'viz-baseline' : 'viz-grid'} />
              <text x={M.left - 6} y={y(t)} dy="0.32em" textAnchor="end" className="viz-tick">{compactNumber(t)}</text>
            </g>
          ))}
          {dates.map((d, i) => {
            // Every k-th day plus the last; skip a regular tick that would crowd the last one.
            const show = i === n - 1 || (i % xLabelEvery === 0 && n - 1 - i >= xLabelEvery / 2);
            if (!show) return null;
            // Edge ticks are anchored inward so they never run under the y-axis or the end labels.
            const anchor = n > 1 && i === 0 ? 'start' : n > 1 && i === n - 1 ? 'end' : 'middle';
            return <text key={d} x={x(i)} y={height - 8} textAnchor={anchor} className="viz-tick">{shortDate(d)}</text>;
          })}
          {series.map((s) => (
            <path key={s.key} d={path(s.values)} fill="none" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round"
              className={`viz-stroke-${s.slot}`} />
          ))}
          {endLabels.map((l) => (
            <g key={l.s.key}>
              <circle cx={x(l.i)} cy={y(l.v)} r="4" className={`viz-fill-${l.s.slot} viz-ring`} />
              <text x={M.left + plotW + 10} y={l.ly} dy="0.32em" className="viz-end-label">
                <tspan className="viz-end-value">{formatValue(l.v)}</tspan> {l.s.label}
              </text>
            </g>
          ))}
          {hover !== null && (
            <g pointerEvents="none">
              <line x1={x(hover)} x2={x(hover)} y1={M.top} y2={M.top + plotH} className="viz-crosshair" />
              {series.map((s) => s.values[hover] !== null && (
                <circle key={s.key} cx={x(hover)} cy={y(s.values[hover] as number)} r="4" className={`viz-fill-${s.slot} viz-ring`} />
              ))}
            </g>
          )}
          <rect x={M.left} y={M.top} width={Math.max(plotW, 1)} height={plotH} fill="transparent"
            onPointerMove={onMove} onPointerLeave={() => setHover(null)} />
        </svg>
        {hover !== null && (
          <div className="viz-tooltip small shadow-sm" role="status"
            style={{ top: M.top, ...(tipLeft ? { right: width - x(hover) + 12 } : { left: x(hover) + 12 }) }}>
            <div className="viz-tooltip-date">{shortDate(dates[hover])}</div>
            {series.map((s) => (
              <div key={s.key} className="d-flex align-items-center gap-2">
                <svg width="12" height="4" aria-hidden="true"><line x1="0" y1="2" x2="12" y2="2" className={`viz-stroke-${s.slot}`} strokeWidth="2" /></svg>
                <strong>{s.values[hover] === null ? '—' : formatValue(s.values[hover] as number)}</strong>
                <span className="viz-muted">{s.label}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </figure>
  );
}
