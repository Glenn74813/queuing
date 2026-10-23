import { useId, type InputHTMLAttributes, type ReactNode } from 'react';

interface BaseProps {
  label: string;
  error?: string;
  hint?: ReactNode;
  className?: string;
}

type TextFieldProps = BaseProps &
  Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value' | 'className'> & {
    value: string | number;
    onChange: (value: string) => void;
  };

/** Labelled input with Bootstrap validation feedback wired to aria-describedby. */
export function TextField({ label, error, hint, className = 'mb-3', value, onChange, required, ...rest }: TextFieldProps) {
  const id = useId();
  const describedBy = [hint ? `${id}-hint` : '', error ? `${id}-err` : ''].filter(Boolean).join(' ') || undefined;
  return (
    <div className={className}>
      <label htmlFor={id} className="form-label">
        {label} {required && <span aria-hidden="true">*</span>}
      </label>
      <input
        id={id}
        className={`form-control ${error ? 'is-invalid' : ''}`}
        value={value}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        onChange={(e) => onChange(e.target.value)}
        {...rest}
      />
      {hint && <div id={`${id}-hint`} className="form-text">{hint}</div>}
      {error && <div id={`${id}-err`} className="invalid-feedback">{error}</div>}
    </div>
  );
}

interface TextAreaProps extends BaseProps {
  value: string;
  onChange: (value: string) => void;
  rows?: number;
  required?: boolean;
}

export function TextAreaField({ label, error, hint, className = 'mb-3', value, onChange, rows = 3, required }: TextAreaProps) {
  const id = useId();
  return (
    <div className={className}>
      <label htmlFor={id} className="form-label">
        {label} {required && <span aria-hidden="true">*</span>}
      </label>
      <textarea id={id} className={`form-control ${error ? 'is-invalid' : ''}`} rows={rows} value={value}
        required={required} onChange={(e) => onChange(e.target.value)} aria-describedby={hint ? `${id}-hint` : undefined} />
      {hint && <div id={`${id}-hint`} className="form-text">{hint}</div>}
      {error && <div className="invalid-feedback">{error}</div>}
    </div>
  );
}

export interface Option {
  value: string;
  label: string;
}

interface SelectProps extends BaseProps {
  value: string;
  onChange: (value: string) => void;
  options: Option[];
  /** Adds an empty first option with this label. */
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
}

export function SelectField({ label, error, hint, className = 'mb-3', value, onChange, options, placeholder, required, disabled }: SelectProps) {
  const id = useId();
  return (
    <div className={className}>
      <label htmlFor={id} className="form-label">
        {label} {required && <span aria-hidden="true">*</span>}
      </label>
      <select id={id} className={`form-select ${error ? 'is-invalid' : ''}`} value={value} required={required}
        disabled={disabled} onChange={(e) => onChange(e.target.value)}>
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {options.map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
      {hint && <div className="form-text">{hint}</div>}
      {error && <div className="invalid-feedback">{error}</div>}
    </div>
  );
}

interface CheckProps {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  className?: string;
  hint?: ReactNode;
}

export function CheckField({ label, checked, onChange, className = 'mb-3', hint }: CheckProps) {
  const id = useId();
  return (
    <div className={`form-check ${className}`}>
      <input id={id} type="checkbox" className="form-check-input" checked={checked} onChange={(e) => onChange(e.target.checked)}
        aria-describedby={hint ? `${id}-hint` : undefined} />
      <label htmlFor={id} className="form-check-label">{label}</label>
      {hint && <div id={`${id}-hint`} className="form-text">{hint}</div>}
    </div>
  );
}

/** Converts a string-union list into select options with readable labels. */
export function enumOptions(values: readonly string[]): Option[] {
  return values.map((v) => ({ value: v, label: v.replace(/_/g, ' ') }));
}
