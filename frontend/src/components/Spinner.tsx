interface Props {
  label?: string;
  fullPage?: boolean;
  small?: boolean;
}

export function Spinner({ label = 'Loading…', fullPage = false, small = false }: Props) {
  const spinner = (
    <div className="d-flex align-items-center gap-2" role="status" aria-live="polite">
      <span className={`spinner-border text-primary ${small ? 'spinner-border-sm' : ''}`} aria-hidden="true" />
      <span className={small ? 'visually-hidden' : ''}>{label}</span>
    </div>
  );
  if (!fullPage) return spinner;
  return <div className="d-flex justify-content-center align-items-center min-vh-100">{spinner}</div>;
}
