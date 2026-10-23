import type { ReactNode } from 'react';

interface SectionProps {
  id: string;
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
}

/** A titled block of a page: heading, optional one-line description and controls on the right. */
export function Section({ id, title, description, actions, children }: SectionProps) {
  return (
    <section className="section" aria-labelledby={id}>
      <div className="section-header">
        <div>
          <h2 id={id} className="section-title">{title}</h2>
          {description && <p className="section-desc">{description}</p>}
        </div>
        {actions && <div className="d-flex flex-wrap align-items-center gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  );
}

interface KpiProps {
  label: string;
  value: ReactNode;
  unit?: string;
  caption?: ReactNode;
}

/** Headline number tile: uppercase label, large tabular value with an optional unit and caption. */
export function KpiCard({ label, value, unit, caption }: KpiProps) {
  return (
    <div className="col-6 col-xl-3">
      <div className="surface kpi">
        <div className="kpi-label">{label}</div>
        <div className="kpi-value">
          {value}
          {unit && value !== '—' && <span className="kpi-unit">{unit}</span>}
        </div>
        {caption && <div className="kpi-caption">{caption}</div>}
      </div>
    </div>
  );
}
