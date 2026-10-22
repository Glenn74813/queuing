import qrcode from 'qrcode-generator';
import { useMemo } from 'react';

/** Encodes `value` as a QR code image (error correction M, auto-sized version). */
export function qrDataUrl(value: string, cellSize = 4): string {
  const qr = qrcode(0, 'M');
  qr.addData(value);
  qr.make();
  return qr.createDataURL(cellSize, cellSize * 2);
}

export function QrCode({ value, label, size = 180, className }: { value: string; label: string; size?: number; className?: string }) {
  const src = useMemo(() => qrDataUrl(value), [value]);
  return <img src={src} alt={label} width={size} height={size} className={className} style={{ imageRendering: 'pixelated' }} />;
}
