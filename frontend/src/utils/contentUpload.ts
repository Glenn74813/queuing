import type { ContentType } from '../api/types';

const MB = 1024 * 1024;

/** Allowed uploads per contract: PNG, JPEG, WebP (≤ 10 MB) and MP4/WebM (≤ 200 MB). */
export const UPLOAD_RULES: Record<'IMAGE' | 'VIDEO', { mimeTypes: string[]; maxBytes: number; accept: string }> = {
  IMAGE: { mimeTypes: ['image/png', 'image/jpeg', 'image/webp'], maxBytes: 10 * MB, accept: '.png,.jpg,.jpeg,.webp' },
  VIDEO: { mimeTypes: ['video/mp4', 'video/webm'], maxBytes: 200 * MB, accept: '.mp4,.webm' },
};

/** Returns an error message, or null when the file is acceptable for the given content type. */
export function validateUpload(file: Pick<File, 'type' | 'size'> | null, type: ContentType): string | null {
  if (type === 'ANNOUNCEMENT') return file ? 'Announcements do not take a file.' : null;
  if (!file) return `A ${type === 'IMAGE' ? 'PNG, JPEG or WebP image' : 'MP4 or WebM video'} is required.`;
  const rule = UPLOAD_RULES[type];
  if (!rule.mimeTypes.includes(file.type)) return `File type ${file.type || 'unknown'} is not allowed for ${type}.`;
  if (file.size > rule.maxBytes) return `File is too large (max ${rule.maxBytes / MB} MB).`;
  return null;
}

/**
 * Detects the real media type from leading bytes (the server validates magic bytes too; this just gives
 * earlier feedback). Returns null for unrecognised content.
 */
export function sniffMime(b: Uint8Array): string | null {
  const at = (i: number, ...vals: number[]) => vals.every((v, k) => b[i + k] === v);
  if (at(0, 0x89, 0x50, 0x4e, 0x47)) return 'image/png';
  if (at(0, 0xff, 0xd8, 0xff)) return 'image/jpeg';
  if (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) return 'image/webp';
  if (at(4, 0x66, 0x74, 0x79, 0x70)) return 'video/mp4'; // "ftyp" box
  if (at(0, 0x1a, 0x45, 0xdf, 0xa3)) return 'video/webm'; // EBML
  return null;
}
