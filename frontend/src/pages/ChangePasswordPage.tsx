import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { toApiError } from '../api/client';
import type { ApiErrorBody } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ErrorAlert } from '../components/ErrorAlert';
import { PASSWORD_HINT, passwordPolicyError } from '../utils/password';

export function ChangePasswordPage() {
  const { me, login, logout } = useAuth();
  const navigate = useNavigate();
  const [currentPassword, setCurrent] = useState('');
  const [newPassword, setNew] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);

  const policyErr = newPassword ? passwordPolicyError(newPassword) : null;
  const mismatch = confirm.length > 0 && confirm !== newPassword;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (policyErr || mismatch) return;
    setBusy(true);
    setError(null);
    try {
      await authApi.changePassword({ currentPassword, newPassword });
      // The server revokes every refresh token on a password change, so sign in again to get a
      // fresh token pair; otherwise the session would end at the next page load or token expiry.
      if (me) {
        await login(me.username, newPassword);
      }
      navigate('/', { replace: true });
    } catch (err) {
      setError(toApiError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="min-vh-100 d-flex align-items-center justify-content-center bg-body-tertiary p-3">
      <div className="card shadow-sm" style={{ maxWidth: 460, width: '100%' }}>
        <div className="card-body p-4">
          <h1 className="h4">Change password</h1>
          {me?.mustChangePassword && (
            <div className="alert alert-warning" role="status">
              You must set a new password before continuing.
            </div>
          )}
          <ErrorAlert error={error} />
          <form onSubmit={submit} noValidate>
            <div className="mb-3">
              <label htmlFor="cur" className="form-label">
                Current password
              </label>
              <input id="cur" type="password" className="form-control" autoComplete="current-password" required
                value={currentPassword} onChange={(e) => setCurrent(e.target.value)} />
            </div>
            <div className="mb-3">
              <label htmlFor="new" className="form-label">
                New password
              </label>
              <input id="new" type="password" className={`form-control ${policyErr ? 'is-invalid' : ''}`}
                autoComplete="new-password" required aria-describedby="pw-hint"
                value={newPassword} onChange={(e) => setNew(e.target.value)} />
              <div id="pw-hint" className="form-text">{PASSWORD_HINT}</div>
              {policyErr && <div className="invalid-feedback">{policyErr}</div>}
            </div>
            <div className="mb-3">
              <label htmlFor="confirm" className="form-label">
                Confirm new password
              </label>
              <input id="confirm" type="password" className={`form-control ${mismatch ? 'is-invalid' : ''}`}
                autoComplete="new-password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} />
              {mismatch && <div className="invalid-feedback">Passwords do not match.</div>}
            </div>
            <div className="d-flex gap-2">
              <button type="submit" className="btn btn-primary"
                disabled={busy || !currentPassword || !newPassword || !!policyErr || mismatch || !confirm}>
                {busy ? 'Saving…' : 'Change password'}
              </button>
              {me?.mustChangePassword ? (
                <button type="button" className="btn btn-outline-secondary" onClick={() => void logout()}>
                  Sign out
                </button>
              ) : (
                <button type="button" className="btn btn-outline-secondary" onClick={() => navigate(-1)}>
                  Cancel
                </button>
              )}
            </div>
          </form>
        </div>
      </div>
    </main>
  );
}
