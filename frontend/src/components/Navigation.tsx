import React from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export const Navigation: React.FC = () => {
  const { user, logout } = useAuth();

  const navItems = [
    { name: 'Dashboard', path: '/', icon: '📊' },
    { name: 'Jobs Feed', path: '/jobs', icon: '💼' },
    { name: 'Profile', path: '/profile', icon: '👤' },
    { name: 'Preferences', path: '/preferences', icon: '⚙️' },
    { name: 'Models & Routing', path: '/models', icon: '🤖' },
    { name: 'Job Sources', path: '/sources', icon: '🌐' },
    { name: 'Logs & Audit', path: '/logs', icon: '📜' },
  ];

  return (
    <aside className="w-64 bg-slate-900 border-r border-slate-800 flex flex-col flex-shrink-0 min-h-screen">
      <div className="p-6 border-b border-slate-800 flex items-center gap-3">
        <div className="w-9 h-9 rounded-lg bg-indigo-600 flex items-center justify-center font-bold text-white shadow-md shadow-indigo-600/30">
          AI
        </div>
        <div>
          <h1 className="font-bold text-white text-base leading-tight">Job Agent</h1>
          <p className="text-xs text-slate-400 font-mono">Phase 1 Modular MVP</p>
        </div>
      </div>

      <nav className="flex-1 p-4 space-y-1.5 overflow-y-auto">
        {navItems.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3.5 py-2.5 rounded-lg text-sm font-medium transition-all ${
                isActive
                  ? 'bg-indigo-600/15 text-indigo-400 border border-indigo-500/20 font-semibold'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`
            }
          >
            <span className="text-base">{item.icon}</span>
            <span>{item.name}</span>
          </NavLink>
        ))}
      </nav>

      {user && (
        <div className="p-4 border-t border-slate-800 bg-slate-900/50">
          <div className="flex items-center justify-between">
            <div className="overflow-hidden">
              <p className="text-xs font-semibold text-white truncate">{user.displayName}</p>
              <p className="text-[11px] text-slate-400 truncate">{user.email}</p>
            </div>
            <button
              onClick={logout}
              title="Sign Out"
              className="p-1.5 rounded-md hover:bg-slate-800 text-slate-400 hover:text-red-400 transition-colors"
            >
              🚪
            </button>
          </div>
        </div>
      )}
    </aside>
  );
};
