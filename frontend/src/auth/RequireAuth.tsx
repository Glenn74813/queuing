import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Spinner } from '../components/Spinner';
import { useAuth } from './AuthContext';

/** Guards staff routes: requires a session and forces the change-password screen when flagged. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, me } = useAuth();
  const location = useLocation();

  if (status === 'loading') return <Spinner label="Restoring session…" fullPage />;
  if (status === 'anonymous' || !me) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (me.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}
