import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { notificationsApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { NOTIFICATION_META } from '../constants/enums';
import { relativeTime } from '../utils/datetime';

// The backend has no read/unread tracking (documented gap), so "new" here means
// "created since this user last opened the notification list on this device" — a
// localStorage timestamp, not a server-provided unread count. The wording says
// "new since your last visit" rather than "N unread" so the UI doesn't overclaim.

const LAST_SEEN_PREFIX = 'is.notifications.lastSeen.';

export function readLastSeen(userId) {
  if (!userId) return null;
  try {
    return localStorage.getItem(LAST_SEEN_PREFIX + userId);
  } catch {
    return null;
  }
}

export function writeLastSeen(userId, iso = new Date().toISOString()) {
  if (!userId) return;
  try {
    localStorage.setItem(LAST_SEEN_PREFIX + userId, iso);
  } catch {
    // Storage blocked — the badge just won't persist across reloads.
  }
}

export function countNew(notifications, lastSeen) {
  if (!notifications?.length) return 0;
  if (!lastSeen) return notifications.length;
  const cutoff = new Date(lastSeen).getTime();
  return notifications.filter((n) => new Date(n.createdAt).getTime() > cutoff).length;
}

export function NotificationBell() {
  const { userId } = useAuth();
  const [items, setItems] = useState([]);
  const [open, setOpen] = useState(false);
  const [lastSeen, setLastSeen] = useState(() => readLastSeen(userId));
  const boxRef = useRef(null);

  useEffect(() => {
    let alive = true;
    const load = () =>
      notificationsApi
        .list()
        .then((data) => {
          if (alive) setItems(data || []);
        })
        .catch(() => {
          // A failing bell must never break the shell around it.
        });
    load();
    const timer = setInterval(load, 60000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [userId]);

  useEffect(() => {
    if (!open) return undefined;
    const onClick = (e) => {
      if (boxRef.current && !boxRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);

  const newCount = useMemo(() => countNew(items, lastSeen), [items, lastSeen]);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next) {
      const now = new Date().toISOString();
      writeLastSeen(userId, now);
      setLastSeen(now);
    }
  };

  return (
    <div className="relative" ref={boxRef}>
      <button
        type="button"
        onClick={toggle}
        className="relative rounded-md p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-800"
        aria-label={`Notifications${newCount ? `, ${newCount} new` : ''}`}
      >
        <span className="text-lg leading-none">🔔</span>
        {newCount > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white">
            {newCount > 9 ? '9+' : newCount}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-30 mt-2 w-80 rounded-xl border border-slate-200 bg-white shadow-lg">
          <div className="flex items-center justify-between border-b border-slate-100 px-4 py-2.5">
            <p className="text-sm font-semibold text-slate-900">Notifications</p>
            {newCount > 0 && (
              <span className="text-xs text-slate-500">{newCount} new since your last visit</span>
            )}
          </div>
          <div className="max-h-80 overflow-y-auto">
            {items.length === 0 ? (
              <p className="px-4 py-6 text-center text-xs text-slate-500">Nothing yet.</p>
            ) : (
              items.slice(0, 8).map((n) => {
                const meta = NOTIFICATION_META[n.type] || { icon: '•', label: n.type };
                const isNew = lastSeen && new Date(n.createdAt) > new Date(lastSeen);
                return (
                  <div
                    key={n.id}
                    className={`flex gap-3 border-b border-slate-50 px-4 py-2.5 ${isNew ? 'bg-brand-50/50' : ''}`}
                  >
                    <span className="text-base leading-none">{meta.icon}</span>
                    <div className="min-w-0 flex-1">
                      <p className="text-xs font-medium text-slate-800">{meta.label}</p>
                      <p className="text-[11px] text-slate-500">{relativeTime(n.createdAt)}</p>
                    </div>
                  </div>
                );
              })
            )}
          </div>
          <Link
            to="/notifications"
            onClick={() => setOpen(false)}
            className="block border-t border-slate-100 px-4 py-2.5 text-center text-xs font-medium text-brand-700 hover:bg-slate-50"
          >
            View all
          </Link>
        </div>
      )}
    </div>
  );
}
