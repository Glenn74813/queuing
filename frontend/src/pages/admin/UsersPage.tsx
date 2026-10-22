import { useState, type FormEvent } from 'react';
import { toApiError } from '../../api/client';
import { orgApi } from '../../api/org';
import type { AssignmentInput, User, UserStatus } from '../../api/types';
import { usersApi } from '../../api/users';
import { useAuth } from '../../auth/AuthContext';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { SelectField, TextField, enumOptions } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { Modal } from '../../components/Modal';
import { PageHeader } from '../../components/PageHeader';
import { ReasonDialog } from '../../components/ReasonDialog';
import { StatusBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime } from '../../utils/format';
import { PASSWORD_HINT, passwordPolicyError } from '../../utils/password';
import { AssignmentsEditor, assignmentsError, toAssignmentInputs } from './AssignmentsEditor';

const STATUSES: UserStatus[] = ['ACTIVE', 'SUSPENDED', 'DEACTIVATED'];
const PAGE_SIZE = 20;

type Dialog =
  | { kind: 'create' }
  | { kind: 'edit' | 'status' | 'password' | 'assignments' | 'sessions'; user: User }
  | null;

/** Reference data needed by the assignment editor (loaded only when a dialog needs it). */
function useAssignmentRefs(enabled: boolean) {
  return useAsync(
    async () => {
      // Regions/sites are optional helpers: a scoped admin may not be allowed to list them all.
      const [roles, regions, sites] = await Promise.all([
        usersApi.listRoles(),
        orgApi.listRegions().catch(() => []),
        orgApi.listSites().catch(() => []),
      ]);
      return { roles, regions, sites };
    },
    [],
    enabled,
  );
}

export function UsersPage() {
  const { can } = useAuth();
  const canManage = can('USER_MANAGE');
  const [query, setQuery] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const users = useAsync(() => usersApi.list({ q, page, size: PAGE_SIZE }), [q, page]);
  const [dialog, setDialog] = useState<Dialog>(null);
  const refs = useAssignmentRefs(canManage);
  const [notice, setNotice] = useState<string | null>(null);
  const reload = () => void users.reload();

  const search = (e: FormEvent) => {
    e.preventDefault();
    setPage(0);
    setQ(query.trim());
  };

  return (
    <>
      <PageHeader title="Users" actions={canManage && (
        <button type="button" className="btn btn-primary" onClick={() => setDialog({ kind: 'create' })}>New user</button>
      )} />
      <form className="row g-2 mb-3" role="search" onSubmit={search}>
        <div className="col-sm-8 col-md-6">
          <label htmlFor="user-q" className="visually-hidden">Search users</label>
          <input id="user-q" type="search" className="form-control" placeholder="Search by username, name or email"
            value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <div className="col-auto">
          <button type="submit" className="btn btn-outline-primary">Search</button>
        </div>
      </form>
      <ErrorAlert error={users.error ?? refs.error} />
      <div aria-live="polite">
        {notice && (
          <div className="alert alert-success alert-dismissible">
            {notice}
            <button type="button" className="btn-close" aria-label="Dismiss" onClick={() => setNotice(null)} />
          </div>
        )}
      </div>
      <DataTable
        caption="Users"
        loading={users.loading}
        rows={users.data?.content}
        rowKey={(u) => u.id}
        paging={users.data && { page: users.data.page, totalPages: users.data.totalPages, totalElements: users.data.totalElements, onPageChange: setPage }}
        columns={[
          { key: 'username', header: 'Username' },
          { key: 'fullName', header: 'Name' },
          { key: 'email', header: 'Email' },
          { key: 'status', header: 'Status', render: (u) => (
            <>
              <StatusBadge status={u.status} />
              {u.lockedUntil && new Date(u.lockedUntil) > new Date() && <span className="badge text-bg-danger ms-1">LOCKED</span>}
            </>
          ) },
          { key: 'roles', header: 'Roles', render: (u) => u.assignments.map((a) =>
            `${a.roleName}${a.siteName ? ` @ ${a.siteName}` : a.regionName ? ` @ ${a.regionName}` : ''}`).join(', ') || '—' },
          { key: 'lastLoginAt', header: 'Last login', render: (u) => formatDateTime(u.lastLoginAt) },
          ...(canManage ? [{
            key: 'actions', header: <span className="visually-hidden">Actions</span>, className: 'text-end text-nowrap',
            render: (u: User) => (
              <div className="btn-group btn-group-sm">
                <button type="button" className="btn btn-outline-primary" onClick={() => setDialog({ kind: 'edit', user: u })}>Edit</button>
                <button type="button" className="btn btn-outline-primary" onClick={() => setDialog({ kind: 'assignments', user: u })}>Roles</button>
                <button type="button" className="btn btn-outline-warning" onClick={() => setDialog({ kind: 'status', user: u })}>Status</button>
                <button type="button" className="btn btn-outline-secondary" onClick={() => setDialog({ kind: 'password', user: u })}>Reset password</button>
                <button type="button" className="btn btn-outline-secondary" onClick={() => setDialog({ kind: 'sessions', user: u })}>Sessions</button>
              </div>
            ),
          }] : []),
        ]}
      />

      {dialog?.kind === 'create' && refs.data && (
        <CreateUserDialog refs={refs.data} onClose={() => setDialog(null)} onSaved={reload} />
      )}
      {dialog?.kind === 'edit' && <EditUserDialog user={dialog.user} onClose={() => setDialog(null)} onSaved={reload} />}
      {dialog?.kind === 'assignments' && refs.data && (
        <AssignmentsDialog user={dialog.user} refs={refs.data} onClose={() => setDialog(null)} onSaved={reload} />
      )}
      {dialog?.kind === 'status' && <StatusDialog user={dialog.user} onClose={() => setDialog(null)} onSaved={reload} />}
      {dialog?.kind === 'password' && (
        <ResetPasswordDialog user={dialog.user} onClose={() => setDialog(null)} onDone={setNotice} />
      )}
      {dialog?.kind === 'sessions' && <SessionsDialog user={dialog.user} onClose={() => setDialog(null)} />}
    </>
  );
}

type Refs = NonNullable<ReturnType<typeof useAssignmentRefs>['data']>;

function CreateUserDialog({ refs, onClose, onSaved }: { refs: Refs; onClose: () => void; onSaved: () => void }) {
  const [username, setUsername] = useState('');
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [assignments, setAssignments] = useState<AssignmentInput[]>([{ roleCode: '' }]);
  return (
    <FormModal title="New user" size="lg" submitLabel="Create user"
      validate={() =>
        (!username || !fullName || !email ? 'Username, name and email are required.' : null) ??
        passwordPolicyError(password) ??
        assignmentsError(assignments, refs.roles)}
      onSubmit={async () => {
        await usersApi.create({ username, fullName, email, password, assignments });
        onSaved();
      }}
      onClose={onClose}>
      {(fe) => (
        <>
          <div className="row">
            <TextField className="col-md-6 mb-3" label="Username" required autoComplete="off" value={username} onChange={setUsername} error={fe('username')} />
            <TextField className="col-md-6 mb-3" label="Full name" required value={fullName} onChange={setFullName} error={fe('fullName')} />
          </div>
          <div className="row">
            <TextField className="col-md-6 mb-3" label="Email" type="email" required value={email} onChange={setEmail} error={fe('email')} />
            <TextField className="col-md-6 mb-3" label="Initial password" type="password" autoComplete="new-password" required
              value={password} onChange={setPassword} error={fe('password')} hint={`${PASSWORD_HINT} The user must change it at first login.`} />
          </div>
          <AssignmentsEditor value={assignments} onChange={setAssignments} {...refs} />
        </>
      )}
    </FormModal>
  );
}

function EditUserDialog({ user, onClose, onSaved }: { user: User; onClose: () => void; onSaved: () => void }) {
  const [fullName, setFullName] = useState(user.fullName);
  const [email, setEmail] = useState(user.email);
  return (
    <FormModal title={`Edit ${user.username}`} onClose={onClose}
      validate={() => (!fullName || !email ? 'Name and email are required.' : null)}
      onSubmit={async () => {
        await usersApi.update(user.id, { fullName, email });
        onSaved();
      }}>
      {(fe) => (
        <>
          <TextField label="Full name" required value={fullName} onChange={setFullName} error={fe('fullName')} />
          <TextField label="Email" type="email" required value={email} onChange={setEmail} error={fe('email')} />
        </>
      )}
    </FormModal>
  );
}

function AssignmentsDialog({ user, refs, onClose, onSaved }: { user: User; refs: Refs; onClose: () => void; onSaved: () => void }) {
  const [value, setValue] = useState<AssignmentInput[]>(toAssignmentInputs(user.assignments));
  return (
    <FormModal title={`Role assignments — ${user.username}`} size="lg" onClose={onClose}
      validate={() => assignmentsError(value, refs.roles)}
      onSubmit={async () => {
        await usersApi.setAssignments(user.id, value);
        onSaved();
      }}>
      {() => <AssignmentsEditor value={value} onChange={setValue} {...refs} />}
    </FormModal>
  );
}

function StatusDialog({ user, onClose, onSaved }: { user: User; onClose: () => void; onSaved: () => void }) {
  const [status, setStatus] = useState<UserStatus>(user.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE');
  return (
    <ReasonDialog title={`Change status — ${user.username}`} confirmLabel="Change status" variant="warning"
      extra={<SelectField label="New status" value={status} onChange={(v) => setStatus(v as UserStatus)}
        options={enumOptions(STATUSES.filter((s) => s !== user.status))} />}
      onSubmit={(reason) => usersApi.changeStatus(user.id, status, reason).then(onSaved)}
      onClose={onClose} />
  );
}

function ResetPasswordDialog({ user, onClose, onDone }: { user: User; onClose: () => void; onDone: (msg: string) => void }) {
  const [pw, setPw] = useState('');
  return (
    <FormModal title={`Reset password — ${user.username}`} submitLabel="Reset password"
      validate={() => passwordPolicyError(pw)}
      onSubmit={async () => {
        await usersApi.resetPassword(user.id, pw);
        onDone(`Password reset. ${user.username} must change it at next login.`);
      }}
      onClose={onClose}>
      {(fe) => (
        <TextField label="New temporary password" type="password" autoComplete="new-password" required value={pw}
          onChange={setPw} error={fe('newPassword')} hint={PASSWORD_HINT} />
      )}
    </FormModal>
  );
}

/** Short browser/OS label from a user-agent string, e.g. "Chrome on Windows". */
export function describeAgent(ua?: string): string {
  if (!ua) return 'Unknown device';
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome'
    : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS'
    : /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser} on ${os}` : browser;
}

/** Live sign-ins of a user; ending one signs that browser out at once (its next request is refused). */
function SessionsDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const sessions = useAsync(() => usersApi.sessions(user.id), [user.id]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const act = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await sessions.reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const list = sessions.data ?? [];
  return (
    <Modal title={`Sign-in sessions — ${user.username}`} size="lg" onClose={onClose}
      footer={(
        <>
          <button type="button" className="btn btn-outline-danger me-auto" disabled={busy || list.length === 0}
            onClick={() => void act(() => usersApi.revokeAllSessions(user.id))}>
            Sign out everywhere
          </button>
          <button type="button" className="btn btn-secondary" onClick={onClose}>Close</button>
        </>
      )}>
      <ErrorAlert error={sessions.error ?? (error ? toApiError(error) : null)} />
      <DataTable caption={`Sign-in sessions of ${user.username}`} loading={sessions.loading} rows={sessions.data}
        rowKey={(s) => s.id} emptyText="Not signed in anywhere."
        columns={[
          { key: 'device', header: 'Device', render: (s) => (
            <>
              {describeAgent(s.userAgent)}
              {s.current && <span className="badge text-bg-info ms-1">This browser</span>}
              <div className="small text-body-secondary">{s.ipAddress ?? '—'}</div>
            </>
          ) },
          { key: 'startedAt', header: 'Signed in', render: (s) => formatDateTime(s.startedAt) },
          { key: 'lastActiveAt', header: 'Last token refresh', render: (s) => formatDateTime(s.lastActiveAt) },
          { key: 'actions', header: <span className="visually-hidden">Actions</span>, className: 'text-end',
            render: (s) => (
              <button type="button" className="btn btn-sm btn-outline-danger" disabled={busy}
                onClick={() => void act(() => usersApi.revokeSession(user.id, s.id))}>
                Sign out
              </button>
            ) },
        ]} />
      <p className="small text-body-secondary mb-0">
        Resetting the password, suspending the account or the user changing their password also ends all sessions.
        {list.some((s) => s.current) && ' These are your own sessions: signing out "This browser" or everywhere signs you out here too.'}
      </p>
    </Modal>
  );
}
