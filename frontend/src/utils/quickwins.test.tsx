import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { Me } from '../api/types';
import { AuthContext, type AuthContextValue } from '../auth/AuthContext';
import { IDLE_WARNING_SECONDS, IdleSignOut } from '../components/IdleSignOut';
import { QrCode } from '../components/QrCode';
import { ticketNumberSample } from '../pages/admin/OrgPage';
import { describeAgent } from '../pages/admin/UsersPage';
import { ticketLink } from './ticketLink';

describe('ticketLink', () => {
  it('uses the configured base, else this origin', () => {
    expect(ticketLink('abc_-1', 'https://qms.example/')).toBe('https://qms.example/t/abc_-1');
    expect(ticketLink('tok', null)).toBe(`${window.location.origin}/t/tok`);
  });
});

describe('ticketNumberSample', () => {
  it('pads like the server format', () => {
    expect(ticketNumberSample('A', 7, '-', 3)).toBe('A-007');
    expect(ticketNumberSample('AB', 7, '', 4)).toBe('AB0007');
    expect(ticketNumberSample('A', 12345, '-', 3)).toBe('A-12345');
  });
});

describe('describeAgent', () => {
  it('names common browsers', () => {
    expect(describeAgent('Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/130.0 Safari/537.36 Edg/130.0')).toBe('Edge on Windows');
    expect(describeAgent('Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36')).toBe('Chrome on Android');
    expect(describeAgent(undefined)).toBe('Unknown device');
  });
});

describe('QrCode', () => {
  it('renders an image with alt text', () => {
    render(<QrCode value="https://qms.example/t/abc" label="Ticket QR" />);
    const img = screen.getByAltText('Ticket QR') as HTMLImageElement;
    expect(img.src).toMatch(/^data:image\/gif;base64,/);
  });
});

describe('IdleSignOut', () => {
  const me = { id: 'u', username: 'u', fullName: 'U', mustChangePassword: false, permissions: [], assignments: [], sites: [], idleTimeoutMinutes: 2 } as Me;

  function renderWith(logout: () => Promise<void>, minutes = 2) {
    const value = { status: 'authenticated', me: { ...me, idleTimeoutMinutes: minutes }, logout } as unknown as AuthContextValue;
    return render(
      <AuthContext.Provider value={value}>
        <MemoryRouter><IdleSignOut /></MemoryRouter>
      </AuthContext.Provider>,
    );
  }

  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('warns a minute before and signs out when nobody answers', async () => {
    const logout = vi.fn(async () => undefined);
    renderWith(logout);
    act(() => void vi.advanceTimersByTime(60_000 - 1));
    expect(screen.queryByText('Are you still there?')).toBeNull();
    act(() => void vi.advanceTimersByTime(1));
    expect(screen.getByText('Are you still there?')).toBeInTheDocument();
    for (let i = 0; i < IDLE_WARNING_SECONDS; i++) {
      await act(async () => void vi.advanceTimersByTime(1000));
    }
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it('keeps the user signed in when they answer, and is off at 0 minutes', () => {
    const logout = vi.fn(async () => undefined);
    const { unmount } = renderWith(logout);
    act(() => void vi.advanceTimersByTime(60_000));
    fireEvent.click(screen.getByRole('button', { name: 'Stay signed in' }));
    act(() => void vi.advanceTimersByTime(30_000));
    expect(screen.queryByText('Are you still there?')).toBeNull();
    expect(logout).not.toHaveBeenCalled();
    unmount();

    renderWith(logout, 0);
    act(() => void vi.advanceTimersByTime(10 * 60_000));
    expect(screen.queryByText('Are you still there?')).toBeNull();
  });
});
