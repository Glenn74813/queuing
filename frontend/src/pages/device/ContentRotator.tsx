import { useEffect, useState, type ReactNode } from 'react';
import type { Content } from '../../api/types';

interface Props {
  items: Content[];
  className?: string;
  /** Rendered when there is nothing to rotate. */
  empty?: ReactNode;
}

/** Cycles through content items, showing each for its displaySeconds (videos are muted and loop within that time). */
export function ContentRotator({ items, className = '', empty = null }: Props) {
  const [index, setIndex] = useState(0);
  const count = items.length;
  const current = count > 0 ? items[index % count] : undefined;

  useEffect(() => {
    if (!current || count < 2) return;
    const t = window.setTimeout(() => setIndex((i) => (i + 1) % count), Math.max(3, current.displaySeconds) * 1000);
    return () => window.clearTimeout(t);
  }, [current, count]);

  if (!current) return <>{empty}</>;
  return (
    <div className={`d-flex align-items-center justify-content-center h-100 ${className}`}>
      {current.type === 'IMAGE' && current.mediaUrl && <img src={current.mediaUrl} alt={current.title} />}
      {current.type === 'VIDEO' && current.mediaUrl && (
        <video key={current.id} src={current.mediaUrl} autoPlay muted loop playsInline aria-label={current.title} />
      )}
      {current.type === 'ANNOUNCEMENT' && (
        <div className="p-5 text-center text-white">
          <h2 className="display-5">{current.title}</h2>
          {current.body && <p className="fs-3">{current.body}</p>}
        </div>
      )}
    </div>
  );
}

/** Horizontal scrolling ticker of announcement texts. Respects prefers-reduced-motion via CSS. */
export function Ticker({ items }: { items: Content[] }) {
  if (items.length === 0) return null;
  const text = items.map((c) => (c.body ? `${c.title}: ${c.body}` : c.title)).join('   •   ');
  // Roughly constant reading speed regardless of text length.
  const duration = Math.max(20, text.length / 6);
  return (
    <div className="ticker" role="marquee" aria-label="Announcements">
      <span className="ticker-inner" style={{ animationDuration: `${duration}s` }}>{text}</span>
    </div>
  );
}
