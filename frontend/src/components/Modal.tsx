import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

interface Props {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  size?: 'sm' | 'lg' | 'xl';
}

/** Open modals, innermost last, so Escape only closes the top-most one when dialogs are nested. */
const openStack: string[] = [];

/**
 * Lightweight controlled Bootstrap modal (no Bootstrap JS), portalled to <body>. Closes on Escape,
 * moves focus into the dialog on open and restores it to the previously focused element on close.
 */
export function Modal({ title, onClose, children, footer, size }: Props) {
  const id = useId();
  const titleId = `${id}-title`;
  const dialogRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    openStack.push(id);
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const first = dialogRef.current?.querySelector<HTMLElement>(
      'input, select, textarea, button:not(.btn-close), [tabindex]:not([tabindex="-1"])',
    );
    (first ?? dialogRef.current)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && openStack[openStack.length - 1] === id) onCloseRef.current();
    };
    document.addEventListener('keydown', onKey);
    document.body.classList.add('modal-open');
    return () => {
      document.removeEventListener('keydown', onKey);
      openStack.splice(openStack.indexOf(id), 1);
      if (openStack.length === 0) document.body.classList.remove('modal-open');
      previouslyFocused?.focus?.();
    };
  }, [id]);

  return createPortal(
    <>
      <div className="modal-backdrop show" onClick={onClose} />
      <div className="modal d-block" role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}
        onClick={(e) => e.target === e.currentTarget && onClose()}>
        <div className={`modal-dialog modal-dialog-scrollable ${size ? `modal-${size}` : ''}`} ref={dialogRef} tabIndex={-1}>
          <div className="modal-content">
            <div className="modal-header">
              <h2 className="modal-title h5" id={titleId}>
                {title}
              </h2>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">{children}</div>
            {footer && <div className="modal-footer">{footer}</div>}
          </div>
        </div>
      </div>
    </>,
    document.body,
  );
}
