/** Phone e-ticket address encoded in the kiosk QR code: {base}/t/{token}; base defaults to this origin. */
export function ticketLink(token: string, base?: string | null): string {
  return `${(base || window.location.origin).replace(/\/+$/, '')}/t/${encodeURIComponent(token)}`;
}
