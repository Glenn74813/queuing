import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AccountMenu, initials } from './AccountMenu';

function renderMenu(onSignOut = vi.fn()) {
  render(
    <MemoryRouter>
      <AccountMenu name="Maria Santos" role="Servicing Agent · LHIO Quezon City" onSignOut={onSignOut} />
    </MemoryRouter>,
  );
  return onSignOut;
}

describe('AccountMenu', () => {
  it('shows only the name until opened', () => {
    renderMenu();
    const trigger = screen.getByRole('button', { name: 'Account menu, signed in as Maria Santos' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('menu')).toBeNull();
    expect(screen.getByText('MS')).toBeInTheDocument();
  });

  it('opens a menu with the role, change password and sign out; Escape closes it', () => {
    const onSignOut = renderMenu();
    fireEvent.click(screen.getByRole('button', { name: /Account menu/ }));
    expect(screen.getByRole('menu')).toHaveTextContent('Servicing Agent · LHIO Quezon City');
    expect(screen.getByRole('menuitem', { name: 'Change password' })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(screen.getByRole('button', { name: /Account menu/ })).toHaveFocus();

    fireEvent.click(screen.getByRole('button', { name: /Account menu/ }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }));
    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  it('makes initials from the first and last name', () => {
    expect(initials('Maria Dela Cruz Santos')).toBe('MS');
    expect(initials('Admin')).toBe('A');
    expect(initials(undefined)).toBe('?');
  });
});
