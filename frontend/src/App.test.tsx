import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App } from './App';

/** Smoke tests: the app shell renders without a backend. */
describe('App routing', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('sends anonymous users to the login page', async () => {
    render(
      <MemoryRouter initialEntries={['/reports']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <App />
      </MemoryRouter>,
    );
    expect(await screen.findByRole('button', { name: /sign in/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
  });

  it('shows the device setup screen on /kiosk when no device key is stored', () => {
    render(
      <MemoryRouter initialEntries={['/kiosk']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <App />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: /kiosk setup/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/device api key/i)).toBeInTheDocument();
  });

  it('shows the device setup screen on /display', () => {
    render(
      <MemoryRouter initialEntries={['/display']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <App />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: /queue display setup/i })).toBeInTheDocument();
  });
});
