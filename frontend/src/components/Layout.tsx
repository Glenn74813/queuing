import { useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { hasAnyPermission } from '../auth/permissions';
import { NAV_GROUPS, NAV_ITEMS } from '../navigation';
import { AgencyBrand } from './AgencyBrand';
import { SiteSelector } from './SiteSelector';

/** Staff application shell: permission-filtered sidebar, top bar with site selector and account menu. */
export function Layout() {
  const { me, logout } = useAuth();
  const navigate = useNavigate();
  const [navOpen, setNavOpen] = useState(false);
  const visible = NAV_ITEMS.filter((i) => hasAnyPermission(me?.permissions, i.anyOf));

  const onLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="qms-shell d-md-flex">
      <a href="#main" className="visually-hidden-focusable position-absolute top-0 start-0 p-2 bg-white z-3">
        Skip to main content
      </a>
      <nav className="qms-sidebar p-2" aria-label="Main navigation">
        <div className="d-flex justify-content-between align-items-center p-2">
          <Link to="/" className="p-0 text-white">
            <AgencyBrand />
          </Link>
          <button
            type="button"
            className="btn btn-sm btn-outline-light d-md-none"
            aria-expanded={navOpen}
            aria-controls="qms-nav-links"
            onClick={() => setNavOpen((o) => !o)}
          >
            Menu
          </button>
        </div>
        <div id="qms-nav-links" className={`${navOpen ? 'd-block' : 'd-none'} d-md-block`}>
          {NAV_GROUPS.map((group) => {
            const items = visible.filter((i) => i.group === group);
            if (items.length === 0) return null;
            return (
              <div key={group}>
                <div className="nav-group">{group}</div>
                <ul className="list-unstyled mb-0">
                  {items.map((i) => (
                    <li key={i.to}>
                      <NavLink to={i.to} end onClick={() => setNavOpen(false)}>
                        {i.label}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </div>
      </nav>
      <div className="flex-grow-1 d-flex flex-column min-vw-0" style={{ minWidth: 0 }}>
        <header className="d-flex flex-wrap align-items-center justify-content-between gap-2 px-3 py-2 border-bottom bg-body-tertiary">
          <SiteSelector />
          <div className="d-flex align-items-center gap-2">
            <span className="small">
              Signed in as <strong>{me?.fullName}</strong>
            </span>
            <Link to="/change-password" className="btn btn-sm btn-outline-secondary">
              Change password
            </Link>
            <button type="button" className="btn btn-sm btn-outline-danger" onClick={onLogout}>
              Sign out
            </button>
          </div>
        </header>
        <main id="main" className="p-3 flex-grow-1" tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
