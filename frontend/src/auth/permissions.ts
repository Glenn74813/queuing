import type { Permission } from '../api/types';

/** True when the user holds at least one of the required permissions (empty requirement = always allowed). */
export function hasAnyPermission(userPermissions: readonly string[] | undefined, required: readonly Permission[]): boolean {
  if (required.length === 0) return true;
  if (!userPermissions) return false;
  return required.some((p) => userPermissions.includes(p));
}
