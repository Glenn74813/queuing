import type { ReactNode } from 'react';

interface Props {
  title: string;
  subtitle?: ReactNode;
  actions?: ReactNode;
}

export function PageHeader({ title, subtitle, actions }: Props) {
  return (
    <header className="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3 pb-2 border-bottom">
      <div>
        <h1 className="h3 mb-0">{title}</h1>
        {subtitle && <div className="text-body-secondary small">{subtitle}</div>}
      </div>
      {actions && <div className="d-flex flex-wrap gap-2">{actions}</div>}
    </header>
  );
}
