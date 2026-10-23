/** Acronyms kept in capitals when codes are turned into words. */
const ACRONYMS = new Set(['SLA', 'CSV', 'PDF', 'IP', 'ID', 'API', 'LHIO', 'CO', 'RO', 'QR', 'SMS']);

/** "AGENT_SESSION_STARTED" → "Agent session started"; "SLA_POLICY_SAVED" → "SLA policy saved". */
export function humanizeCode(code: string | null | undefined): string {
  if (!code) return '—';
  return code
    .split('_')
    .filter(Boolean)
    .map((w, i) => {
      const up = w.toUpperCase();
      if (ACRONYMS.has(up)) return up;
      const lower = w.toLowerCase();
      return i === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
    })
    .join(' ');
}

/** What a person types in a filter ("Ticket voided", "ticket-voided") → the stored code "TICKET_VOIDED". */
export function toCode(text: string): string {
  return text.trim().replace(/[\s-]+/g, '_').toUpperCase();
}

const ENTITY_NAMES: Record<string, string> = { AppUser: 'User', ServiceItem: 'Catalog service', SiteService: 'Site service' };

/** "ServiceCounter" → "Service counter"; a few internal names get their screen name. */
export function entityLabel(type: string | null | undefined): string {
  if (!type) return '—';
  if (ENTITY_NAMES[type]) return ENTITY_NAMES[type];
  const words = type.replace(/([a-z])([A-Z])/g, '$1 $2').split(' ');
  return [words[0], ...words.slice(1).map((w) => (ACRONYMS.has(w.toUpperCase()) ? w : w.toLowerCase()))].join(' ');
}
