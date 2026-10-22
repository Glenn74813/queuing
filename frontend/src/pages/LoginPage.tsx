import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { toApiError } from '../api/client';
import type { ApiErrorBody } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { AGENCY_NAME, AgencyBrand } from '../components/AgencyBrand';
import { ErrorAlert } from '../components/ErrorAlert';

export function LoginPage() {
  const { login, status } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);

  if (status === 'authenticated') return <Navigate to={from} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const me = await login(username.trim(), password);
      navigate(me.mustChangePassword ? '/change-password' : from, { replace: true });
    } catch (err) {
      setError(toApiError(err));
    } finally {
      setBusy(false);
      setPassword('');
    }
  };

  return (
    <main className="min-vh-100 d-flex align-items-center justify-content-center bg-body-tertiary p-3">
      <div className="card shadow-sm" style={{ maxWidth: 420, width: '100%' }}>
        <div className="card-body p-4">
          <div className="text-primary mb-3">
            <AgencyBrand size="lg" />
          </div>
          <h1 className="h4 mb-1">Queueing Management System</h1>
          <p className="text-body-secondary small">{AGENCY_NAME} — staff sign in</p>
          <ErrorAlert error={error} />
          <form onSubmit={submit}>
            <div className="mb-3">
              <label htmlFor="username" className="form-label">
                Username
              </label>
              <input
                id="username"
                className="form-control"
                autoComplete="username"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </div>
            <div className="mb-3">
              <label htmlFor="password" className="form-label">
                Password
              </label>
              <input
                id="password"
                type="password"
                className="form-control"
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
            <button type="submit" className="btn btn-primary w-100" disabled={busy || !username || !password}>
              {busy ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
        </div>
      </div>
    </main>
  );
}
