import React, { useEffect, useState } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { NotificationBell } from './NotificationBell';

/**
 * The authenticated left sidebar.
 *
 * Robin branding on top, navigation grouped the way a job platform thinks
 * (finding work vs. how the agent works), forest-on-cream selected state, and
 * the user area — notifications and sign out — at the bottom. On small screens
 * the sidebar collapses into a top bar with a drawer.
 *
 * Presentation only: the same routes, the same logout handler and the same
 * NotificationBell as before, just under the new design system.
 */

interface NavItem {
  name: string;
  path: string;
  icon: React.ReactNode;
}

const iconClass = 'h-[18px] w-[18px] shrink-0';

const MAIN_NAV: NavItem[] = [
  {
    name: 'Dashboard',
    path: '/',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <rect x="3.5" y="3.5" width="7" height="9" rx="1.5" stroke="currentColor" strokeWidth="1.6" />
        <rect x="13.5" y="3.5" width="7" height="5" rx="1.5" stroke="currentColor" strokeWidth="1.6" />
        <rect x="3.5" y="15.5" width="7" height="5" rx="1.5" stroke="currentColor" strokeWidth="1.6" />
        <rect x="13.5" y="11.5" width="7" height="9" rx="1.5" stroke="currentColor" strokeWidth="1.6" />
      </svg>
    ),
  },
  {
    name: 'Jobs Feed',
    path: '/jobs',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <rect x="3.5" y="7.5" width="17" height="12" rx="2" stroke="currentColor" strokeWidth="1.6" />
        <path d="M9 7.5V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v1.5" stroke="currentColor" strokeWidth="1.6" />
        <path d="M3.5 12.5h17" stroke="currentColor" strokeWidth="1.6" />
      </svg>
    ),
  },
  {
    name: 'Applications',
    path: '/applications',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <rect x="4.5" y="4.5" width="15" height="15" rx="2" stroke="currentColor" strokeWidth="1.6" />
        <path d="M8.5 9.5h7M8.5 12.5h7M8.5 15.5h4.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    ),
  },
  {
    name: 'Review Queue',
    path: '/review-queue',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <rect x="4.5" y="4.5" width="15" height="15" rx="2" stroke="currentColor" strokeWidth="1.6" />
        <path d="M8.5 9h7M8.5 12h7M8.5 15h4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        <path d="M15.5 16.5l2 2 3.5-3.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    ),
  },
  {
    name: 'Profile',
    path: '/profile',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <circle cx="12" cy="8" r="3.5" stroke="currentColor" strokeWidth="1.6" />
        <path d="M5 20c.8-3.5 3.6-5.5 7-5.5s6.2 2 7 5.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    ),
  },
  {
    name: 'Preferences',
    path: '/preferences',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <path
          d="M5 8h9m3.5 0H19M5 16h3.5M12 16h7"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
        />
        <circle cx="16" cy="8" r="2" stroke="currentColor" strokeWidth="1.6" />
        <circle cx="9.5" cy="16" r="2" stroke="currentColor" strokeWidth="1.6" />
      </svg>
    ),
  },
  {
    name: 'Approval Rules',
    path: '/approval-rules',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <path d="M12 3.5L4 7.5v5c0 4.7 3.4 9 8 10 4.6-1 8-5.3 8-10v-5l-8-4Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
        <path d="M9 12l2 2 4-4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    ),
  },
];

const SYSTEM_NAV: NavItem[] = [
  {
    name: 'Models & Routing',
    path: '/models',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <rect x="4" y="4" width="16" height="16" rx="2.5" stroke="currentColor" strokeWidth="1.6" />
        <path
          d="M9 9.5h1.8v5H9zm4.2 0H15v5h-1.8zM9 12h6"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinecap="round"
        />
      </svg>
    ),
  },
  {
    name: 'Job Sources',
    path: '/sources',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <circle cx="12" cy="12" r="8.5" stroke="currentColor" strokeWidth="1.6" />
        <path d="M3.5 12h17M12 3.5c2.4 2.3 3.6 5.1 3.6 8.5s-1.2 6.2-3.6 8.5c-2.4-2.3-3.6-5.1-3.6-8.5s1.2-6.2 3.6-8.5Z" stroke="currentColor" strokeWidth="1.6" />
      </svg>
    ),
  },
  {
    name: 'Logs & Audit',
    path: '/logs',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className={iconClass} aria-hidden="true">
        <path d="M6 3.5h9L19 7.5v13H6z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
        <path d="M9.5 11h5m-5 3.5h5m-5 3.5h3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    ),
  },
];

const ALL_NAV = [...MAIN_NAV, ...SYSTEM_NAV];

export const Navigation: React.FC = () => {
  const { user, logout } = useAuth();
  const [mobileOpen, setMobileOpen] = useState(false);

  // Close the drawer whenever the route changes.
  useEffect(() => {
    setMobileOpen(false);
  }, [user]);

  return (
    <>
      {/* Mobile top bar */}
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-line bg-surface px-4 py-3 lg:hidden">
        <div className="flex items-center gap-2.5">
          <BrandMark />
          <div className="leading-tight">
            <p className="text-sm font-bold text-ink">Robin</p>
            <p className="text-[10px] font-medium text-ink-muted">Your AI Job Agent</p>
          </div>
        </div>
        <div className="flex items-center gap-1">
          <NotificationBell />
          <button
            type="button"
            onClick={() => setMobileOpen((o) => !o)}
            aria-expanded={mobileOpen}
            aria-label={mobileOpen ? 'Close navigation menu' : 'Open navigation menu'}
            className="rounded-lg p-2 text-ink-soft transition-colors hover:bg-cream-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
          >
            {mobileOpen ? (
              <svg viewBox="0 0 24 24" fill="none" className="h-5 w-5" aria-hidden="true">
                <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              </svg>
            ) : (
              <svg viewBox="0 0 24 24" fill="none" className="h-5 w-5" aria-hidden="true">
                <path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              </svg>
            )}
          </button>
        </div>
      </header>

      {mobileOpen && (
        <div className="border-b border-line bg-surface px-3 pb-4 pt-2 lg:hidden">
          <NavList onNavigate={() => setMobileOpen(false)} />
          {user && <UserArea name={user.displayName} email={user.email} onLogout={logout} />}
        </div>
      )}

      {/* Desktop sidebar */}
      <aside className="sticky top-0 hidden h-screen w-64 shrink-0 flex-col border-r border-line bg-cream-50 lg:flex">
        <div className="flex items-center gap-3 px-5 pb-5 pt-6">
          <BrandMark />
          <div className="leading-tight">
            <p className="text-[15px] font-bold tracking-tight text-ink">Robin</p>
            <p className="text-[11px] font-medium text-ink-muted">Your AI Job Agent</p>
          </div>
        </div>

        <nav aria-label="Main navigation" className="flex-1 overflow-y-auto px-3 pb-4">
          <NavGroup label="Overview" items={MAIN_NAV.slice(0, 2)} />
          <NavGroup label="Career" items={MAIN_NAV.slice(2)} />
          <NavGroup label="Agent operations" items={SYSTEM_NAV} last />
        </nav>

        {user && (
          <div className="border-t border-line px-3 py-3">
            <UserArea name={user.displayName} email={user.email} onLogout={logout} withBell />
          </div>
        )}
      </aside>
    </>
  );
};

const BrandMark: React.FC = () => (
  <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-forest-900 text-base font-bold text-cream-50 shadow-raise">
    R
  </div>
);

const NavGroup: React.FC<{ label: string; items: NavItem[]; last?: boolean }> = ({
  label,
  items,
  last,
}) => (
  <div className={last ? '' : 'mb-5'}>
    <p className="px-3 pb-1.5 pt-3 text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-faint">
      {label}
    </p>
    <div className="space-y-0.5">
      {items.map((item) => (
        <NavLink
          key={item.path}
          to={item.path}
          end={item.path === '/'}
          className={({ isActive }) =>
            `flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 ${
              isActive
                ? 'bg-forest-100 font-semibold text-forest-900'
                : 'text-ink-soft hover:bg-cream-100 hover:text-ink'
            }`
          }
        >
          {item.icon}
          <span>{item.name}</span>
        </NavLink>
      ))}
    </div>
  </div>
);

const NavList: React.FC<{ onNavigate: () => void }> = ({ onNavigate }) => (
  <nav aria-label="Main navigation" className="space-y-0.5">
    {ALL_NAV.map((item) => (
      <NavLink
        key={item.path}
        to={item.path}
        end={item.path === '/'}
        onClick={onNavigate}
        className={({ isActive }) =>
          `flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 ${
            isActive
              ? 'bg-forest-100 font-semibold text-forest-900'
              : 'text-ink-soft hover:bg-cream-100 hover:text-ink'
          }`
        }
      >
        {item.icon}
        <span>{item.name}</span>
      </NavLink>
    ))}
  </nav>
);

const UserArea: React.FC<{ name: string; email: string; onLogout: () => void; withBell?: boolean }> = ({
  name,
  email,
  onLogout,
  withBell = false,
}) => (
  <div className="flex items-center justify-between gap-2 rounded-lg px-2 py-1.5">
    <div className="min-w-0 overflow-hidden">
      <p className="truncate text-xs font-semibold text-ink">{name}</p>
      <p className="truncate text-[11px] text-ink-muted">{email}</p>
    </div>
    <div className="flex shrink-0 items-center gap-1">
      {withBell && <NotificationBell />}
      <button
        type="button"
        onClick={onLogout}
        title="Sign Out"
        aria-label="Sign out"
        className="rounded-md p-1.5 text-ink-muted transition-colors hover:bg-red-50 hover:text-red-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
      >
        <svg viewBox="0 0 24 24" fill="none" className="h-4 w-4" aria-hidden="true">
          <path
            d="M14 4.5H7a1.5 1.5 0 0 0-1.5 1.5v12A1.5 1.5 0 0 0 7 19.5h7m2.5-4L16.5 19.5 19.5 15.5Zm0 0V8.5m0 0L16.5 8.5 19.5 8.5Z"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>
    </div>
  </div>
);
