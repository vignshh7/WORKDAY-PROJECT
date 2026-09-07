import { useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { NotificationBell } from '../components/NotificationBell';
import { RoleBadge } from '../components/StatusBadge';
import { initials } from '../utils/format';

// Nav is derived from role. This is presentation only — the backend enforces every
// role gate itself and will 403 regardless of what the UI chooses to show.
const NAV_BY_ROLE = {
  RECRUITER: [
    { to: '/recruiter', label: 'Overview', end: true },
    { to: '/recruiter/candidates', label: 'Candidates' },
    { to: '/recruiter/jobs', label: 'Jobs' },
    { to: '/recruiter/interviews', label: 'Interviews' },
    { to: '/recruiter/scheduling', label: 'Schedule' },
  ],
  INTERVIEWER: [
    { to: '/interviewer', label: 'My interviews', end: true },
    { to: '/interviewer/availability', label: 'Availability' },
  ],
  CANDIDATE: [
    { to: '/candidate', label: 'My pipeline', end: true },
    { to: '/candidate/interviews', label: 'Interviews' },
    { to: '/candidate/availability', label: 'Availability' },
  ],
  ADMIN: [
    { to: '/admin', label: 'Overview', end: true },
    { to: '/admin/users', label: 'Users' },
    { to: '/admin/audit-logs', label: 'Audit logs' },
    { to: '/recruiter/candidates', label: 'Candidates' },
    { to: '/recruiter/jobs', label: 'Jobs' },
    { to: '/recruiter/scheduling', label: 'Schedule' },
  ],
};

function navClass({ isActive }) {
  return `rounded-md px-3 py-2 text-sm font-medium transition-colors ${
    isActive ? 'bg-brand-50 text-brand-800' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
  }`;
}

export function AppLayout() {
  const { role, name, logout, session } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();
  const nav = NAV_BY_ROLE[role] || [];

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="sticky top-0 z-20 border-b border-slate-200 bg-white">
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-4 px-4">
          <Link to="/" className="flex shrink-0 items-center gap-2">
            <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-600 text-sm font-bold text-white">
              IS
            </span>
            <span className="hidden text-sm font-semibold text-slate-900 sm:block">
              Interview Scheduler
            </span>
          </Link>

          <nav className="hidden flex-1 items-center gap-1 md:flex">
            {nav.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end} className={navClass}>
                {item.label}
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-2">
            <NotificationBell />
            <div className="relative">
              <button
                type="button"
                onClick={() => setMenuOpen((v) => !v)}
                className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-slate-100"
              >
                <span className="flex h-7 w-7 items-center justify-center rounded-full bg-slate-200 text-xs font-semibold text-slate-700">
                  {initials(name)}
                </span>
                <span className="hidden text-sm text-slate-700 sm:block">{name}</span>
              </button>
              {menuOpen && (
                <div
                  className="absolute right-0 z-30 mt-2 w-56 rounded-lg border border-slate-200 bg-white py-1 shadow-lg"
                  onMouseLeave={() => setMenuOpen(false)}
                >
                  <div className="border-b border-slate-100 px-4 py-2">
                    <p className="truncate text-sm font-medium text-slate-900">{name}</p>
                    <p className="truncate text-xs text-slate-500">{session?.email}</p>
                    <div className="mt-1.5">
                      <RoleBadge role={role} />
                    </div>
                  </div>
                  <Link
                    to="/settings/integrations"
                    onClick={() => setMenuOpen(false)}
                    className="block px-4 py-2 text-sm text-slate-700 hover:bg-slate-50"
                  >
                    Settings &amp; integrations
                  </Link>
                  <Link
                    to="/notifications"
                    onClick={() => setMenuOpen(false)}
                    className="block px-4 py-2 text-sm text-slate-700 hover:bg-slate-50"
                  >
                    Notifications
                  </Link>
                  <button
                    type="button"
                    onClick={logout}
                    className="block w-full px-4 py-2 text-left text-sm text-red-700 hover:bg-red-50"
                  >
                    Sign out
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Same nav, stacked, on small screens. */}
        <nav className="flex gap-1 overflow-x-auto border-t border-slate-100 px-4 py-1.5 md:hidden">
          {nav.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className={navClass}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </header>

      <main className="mx-auto max-w-7xl px-4 py-6" key={location.pathname}>
        <Outlet />
      </main>
    </div>
  );
}

export function PageHeader({ title, subtitle, actions, breadcrumb }) {
  return (
    <div className="mb-6">
      {breadcrumb && <div className="mb-1 text-xs text-slate-500">{breadcrumb}</div>}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
          {subtitle && <p className="mt-1 text-sm text-slate-600">{subtitle}</p>}
        </div>
        {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
      </div>
    </div>
  );
}
