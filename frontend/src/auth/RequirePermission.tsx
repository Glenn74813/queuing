import type { ReactNode } from 'react';
import type { Permission } from '../api/types';
import { useAuth } from './AuthContext';

interface Props {
  /** User needs at least one of these permissions. */
  anyOf: Permission[];
  children: ReactNode;
  /** Rendered when access is denied; defaults to a 403 message. Pass null to render nothing. */
  fallback?: ReactNode;
}

export function RequirePermission({ anyOf, children, fallback }: Props) {
  const { can } = useAuth();
  if (can(...anyOf)) return <>{children}</>;
  if (fallback !== undefined) return <>{fallback}</>;
  return (
    <div className="alert alert-warning m-3" role="alert">
      <h2 className="h5">Not authorized</h2>
      <p className="mb-0">You do not have permission to view this page.</p>
    </div>
  );
}
