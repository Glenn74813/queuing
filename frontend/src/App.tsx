import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import { RequirePermission } from './auth/RequirePermission';
import { SiteProvider } from './auth/SiteContext';
import { hasAnyPermission } from './auth/permissions';
import { Layout } from './components/Layout';
import { NAV_ITEMS } from './navigation';
import { AgentConsolePage } from './pages/AgentConsolePage';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { DashboardPage } from './pages/DashboardPage';
import { LoginPage } from './pages/LoginPage';
import { QueueMonitorPage } from './pages/QueueMonitorPage';
import { ReportsPage } from './pages/ReportsPage';
import { ScheduledReportsPage } from './pages/ScheduledReportsPage';
import { AuditPage } from './pages/admin/AuditPage';
import { CatalogPage } from './pages/admin/CatalogPage';
import { ContentPage } from './pages/admin/ContentPage';
import { CountersPage } from './pages/admin/CountersPage';
import { DevicesPage } from './pages/admin/DevicesPage';
import { OrgPage } from './pages/admin/OrgPage';
import { PrioritiesPage } from './pages/admin/PrioritiesPage';
import { SiteServicesPage } from './pages/admin/SiteServicesPage';
import { SlaPage } from './pages/admin/SlaPage';
import { SystemPage } from './pages/admin/SystemPage';
import { UsersPage } from './pages/admin/UsersPage';
import { DisplayPage } from './pages/device/DisplayPage';
import { FeedbackPage } from './pages/device/FeedbackPage';
import { KioskPage } from './pages/device/KioskPage';
import type { ReactElement } from 'react';

/** Sends the user to the first page their permissions allow. */
function HomeRedirect() {
  const { me } = useAuth();
  const first = NAV_ITEMS.find((i) => hasAnyPermission(me?.permissions, i.anyOf));
  if (!first) {
    return (
      <div className="alert alert-info" role="status">
        Your account has no permissions assigned yet. Contact your administrator.
      </div>
    );
  }
  return <Navigate to={first.to} replace />;
}

const PAGES: Record<string, ReactElement> = {
  '/dashboard': <DashboardPage />,
  '/agent': <AgentConsolePage />,
  '/monitor': <QueueMonitorPage />,
  '/admin/org': <OrgPage />,
  '/admin/users': <UsersPage />,
  '/admin/catalog': <CatalogPage />,
  '/admin/site-services': <SiteServicesPage />,
  '/admin/priorities': <PrioritiesPage />,
  '/admin/counters': <CountersPage />,
  '/admin/devices': <DevicesPage />,
  '/admin/sla': <SlaPage />,
  '/content': <ContentPage />,
  '/reports/scheduled': <ScheduledReportsPage />,
  '/reports': <ReportsPage />,
  '/audit': <AuditPage />,
  '/system': <SystemPage />,
};

export function App() {
  return (
    <Routes>
      {/* Unauthenticated device pages (X-Device-Key) */}
      <Route path="/kiosk" element={<KioskPage />} />
      <Route path="/display" element={<DisplayPage />} />
      <Route path="/feedback/:ticketId" element={<FeedbackPage />} />

      {/* Staff application */}
      <Route
        path="/*"
        element={
          <AuthProvider>
            <SiteProvider>
              <StaffRoutes />
            </SiteProvider>
          </AuthProvider>
        }
      />
    </Routes>
  );
}

function StaffRoutes() {
  return (
    <Routes>
      <Route path="login" element={<LoginPage />} />
      <Route
        path="change-password"
        element={
          <RequireAuth>
            <ChangePasswordPage />
          </RequireAuth>
        }
      />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<HomeRedirect />} />
        {NAV_ITEMS.map((item) => (
          <Route
            key={item.to}
            path={item.to.slice(1)}
            element={<RequirePermission anyOf={item.anyOf}>{PAGES[item.to]}</RequirePermission>}
          />
        ))}
        <Route path="*" element={<p className="lead">Page not found.</p>} />
      </Route>
    </Routes>
  );
}
