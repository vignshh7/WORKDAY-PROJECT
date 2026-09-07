import axios from 'axios';
import { getToken, clearSession } from '../auth/tokenStore';

// Blank base URL means "same origin" — Vite's dev proxy forwards /api to :8080.
const baseURL = import.meta.env.VITE_API_BASE_URL || '';

export const api = axios.create({
  baseURL,
  headers: { 'Content-Type': 'application/json' },
  // 120s: the AI endpoints call an LLM, and find-slots/recommend query Google Calendar's
  // freebusy API once per participant (candidate + each eligible interviewer) sequentially,
  // so both are genuinely slow — a 60s timeout was observed to fire ~5s before a legitimate
  // 65s backend response, surfacing as a false "server unreachable" error.
  timeout: 120000,
});

// Attach the JWT to every request. Reading it per-request (not once at module load)
// keeps it correct across login/logout without rebuilding the client.
api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// Registered by AuthProvider so a 401 can bounce the user to /login from anywhere,
// including outside React's render tree (an interceptor has no hook access).
let onUnauthorized = null;
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

api.interceptors.response.use(
  (res) => res,
  (err) => {
    const status = err?.response?.status;
    // 401 = the token is missing or expired: drop it and re-authenticate.
    // 403 is NOT a token problem — the role or ownership check failed, so leave the
    // session alone and let the page render its own "not allowed" state.
    if (status === 401) {
      const isLoginAttempt = err.config?.url?.includes('/api/auth/');
      if (!isLoginAttempt) {
        clearSession();
        if (onUnauthorized) onUnauthorized();
      }
    }
    return Promise.reject(err);
  },
);

export default api;
