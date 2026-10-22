import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import type { Me, Permission } from '../api/types';
import { AuthContext, type AuthContextValue } from './AuthContext';
import { RequirePermission } from './RequirePermission';
import { hasAnyPermission } from './permissions';

function authWith(permissions: string[]): AuthContextValue {
  const me: Me = { id: 'u1', username: 'jdoe', fullName: 'J Doe', mustChangePassword: false, permissions, assignments: [], sites: [], idleTimeoutMinutes: 0 };
  return {
    status: 'authenticated',
    me,
    login: async () => me,
    logout: async () => undefined,
    reloadMe: async () => me,
    can: (...anyOf: Permission[]) => hasAnyPermission(permissions, anyOf),
  };
}

function renderWith(permissions: string[], anyOf: Permission[], fallback?: ReactNode) {
  return render(
    <AuthContext.Provider value={authWith(permissions)}>
      <RequirePermission anyOf={anyOf} fallback={fallback}>
        <p>secret content</p>
      </RequirePermission>
    </AuthContext.Provider>,
  );
}

describe('RequirePermission', () => {
  it('renders children when the user has the permission', () => {
    renderWith(['QUEUE_VIEW'], ['QUEUE_VIEW']);
    expect(screen.getByText('secret content')).toBeInTheDocument();
  });

  it('allows when any one of several permissions is held', () => {
    renderWith(['USER_VIEW'], ['USER_MANAGE', 'USER_VIEW']);
    expect(screen.getByText('secret content')).toBeInTheDocument();
  });

  it('hides children and shows a not-authorized message otherwise', () => {
    renderWith(['QUEUE_VIEW'], ['AUDIT_VIEW']);
    expect(screen.queryByText('secret content')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(/not authorized/i);
  });

  it('renders nothing when fallback is null', () => {
    const { container } = renderWith([], ['AUDIT_VIEW'], null);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('hasAnyPermission', () => {
  it('treats an empty requirement as allowed and missing permissions as denied', () => {
    expect(hasAnyPermission([], [])).toBe(true);
    expect(hasAnyPermission(undefined, ['QUEUE_VIEW'])).toBe(false);
  });
});
