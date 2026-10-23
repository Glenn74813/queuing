import { useEffect, useId, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

const ICON = { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2,
  strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true };

const KeyIcon = () => (
  <svg {...ICON}><circle cx="7.5" cy="15.5" r="4.5" /><path d="m10.7 12.3 9.3-9.3M17 6l3 3M14 9l2 2" /></svg>
);
const SignOutIcon = () => (
  <svg {...ICON}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="m16 17 5-5-5-5M21 12H9" /></svg>
);
const Chevron = ({ open }: { open: boolean }) => (
  <svg {...ICON} className={`account-chevron${open ? ' is-open' : ''}`}><path d="m18 15-6-6-6 6" /></svg>
);

/** "Maria Santos" → "MS". */
export function initials(name: string | undefined): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase() || '?';
}

interface Props {
  name: string | undefined;
  /** Role and scope shown in the menu header, e.g. "Servicing Agent · LHIO Quezon City". */
  role?: string;
  onSignOut: () => void;
  onNavigate?: () => void;
}

/**
 * Account button at the foot of the sidebar: avatar, name and an arrow. It opens a small menu above it
 * with Change password and Sign out. Escape or a click outside closes it; arrow keys move between items.
 */
export function AccountMenu({ name, role, onSignOut, onNavigate }: Props) {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (!open) return;
    root.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const close = (refocus = true) => {
    setOpen(false);
    if (refocus) button.current?.focus();
  };

  const onMenuKey = (e: React.KeyboardEvent) => {
    const items = Array.from(root.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const i = items.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'Escape') {
      e.preventDefault();
      close();
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      items[(i + (e.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length]?.focus();
    } else if (e.key === 'Tab') {
      close(false);
    }
  };

  return (
    <div className="account-menu" ref={root}>
      {open && (
        <div id={menuId} className="account-popover" role="menu" aria-label="Account" onKeyDown={onMenuKey}>
          <div className="account-popover-head">
            <div className="account-popover-name">{name}</div>
            {role && role !== name && <div className="account-popover-role">{role}</div>}
          </div>
          <button type="button" role="menuitem" className="account-item"
            onClick={() => { close(false); onNavigate?.(); navigate('/change-password'); }}>
            <KeyIcon /> Change password
          </button>
          <div className="account-sep" role="separator" />
          <button type="button" role="menuitem" className="account-item is-danger" onClick={() => { close(false); onSignOut(); }}>
            <SignOutIcon /> Sign out
          </button>
        </div>
      )}
      <button ref={button} type="button" className={`account-trigger${open ? ' is-open' : ''}`}
        aria-haspopup="menu" aria-expanded={open} aria-controls={open ? menuId : undefined}
        aria-label={`Account menu, signed in as ${name ?? ''}`} onClick={() => setOpen((o) => !o)}>
        <span className="account-avatar" aria-hidden="true">{initials(name)}</span>
        <span className="account-name text-truncate">{name}</span>
        <Chevron open={open} />
      </button>
    </div>
  );
}
