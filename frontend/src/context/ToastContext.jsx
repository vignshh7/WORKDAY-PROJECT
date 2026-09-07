import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { parseApiError } from '../utils/errors';

const ToastContext = createContext(null);

const TONE_STYLES = {
  success: 'border-green-300 bg-green-50 text-green-900',
  error: 'border-red-300 bg-red-50 text-red-900',
  warning: 'border-amber-300 bg-amber-50 text-amber-900',
  info: 'border-slate-300 bg-white text-slate-900',
};

const TONE_ICON = { success: '✓', error: '!', warning: '!', info: 'i' };

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id) => {
    setToasts((list) => list.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (message, tone = 'info', opts = {}) => {
      const id = nextId.current++;
      const toast = { id, message, tone, title: opts.title || null };
      setToasts((list) => [...list, toast]);
      const ttl = opts.duration ?? (tone === 'error' ? 8000 : 4500);
      if (ttl > 0) setTimeout(() => dismiss(id), ttl);
      return id;
    },
    [dismiss],
  );

  const toast = useMemo(
    () => ({
      show: push,
      success: (m, o) => push(m, 'success', o),
      error: (m, o) => push(m, 'error', o),
      warning: (m, o) => push(m, 'warning', o),
      info: (m, o) => push(m, 'info', o),
      /**
       * One handler for the backend's single error shape. A 403 is stated plainly
       * rather than retried, and a network failure is worded as a reachability
       * problem instead of a server error.
       */
      apiError: (err, fallback) => {
        const parsed = parseApiError(err);
        if (parsed.status === 403) {
          return push(
            parsed.message || "You don't have permission to do that.",
            'error',
            { title: 'Not allowed' },
          );
        }
        if (parsed.isNetwork) {
          return push(parsed.message, 'error', { title: 'Connection failed' });
        }
        return push(parsed.message || fallback || 'Something went wrong.', 'error');
      },
      dismiss,
    }),
    [push, dismiss],
  );

  return (
    <ToastContext.Provider value={toast}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-full max-w-sm flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            role="status"
            className={`pointer-events-auto flex gap-3 rounded-lg border px-4 py-3 shadow-lg ${TONE_STYLES[t.tone]}`}
          >
            <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-black/10 text-xs font-bold">
              {TONE_ICON[t.tone]}
            </span>
            <div className="min-w-0 flex-1 text-sm">
              {t.title && <p className="font-semibold">{t.title}</p>}
              <p className="break-words">{t.message}</p>
            </div>
            <button
              type="button"
              onClick={() => dismiss(t.id)}
              className="shrink-0 text-lg leading-none opacity-50 hover:opacity-100"
              aria-label="Dismiss"
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside <ToastProvider>');
  return ctx;
}
