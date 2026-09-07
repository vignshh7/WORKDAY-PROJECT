import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { authApi, candidatesApi, interviewersApi, usersApi } from '../api/endpoints';
import { setUnauthorizedHandler } from '../api/client';
import { clearSession, getStoredUser, getToken, saveSession } from './tokenStore';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => getStoredUser());
  const [profile, setProfile] = useState(null); // full UserResponse
  // The CANDIDATE / INTERVIEWER profile row keyed to this user, when one exists.
  // Their own pages need the *profile* id, which is not the user id.
  const [roleProfile, setRoleProfile] = useState(null);
  const [bootstrapping, setBootstrapping] = useState(!!getToken());
  const [expired, setExpired] = useState(false);

  const logout = useCallback(() => {
    clearSession();
    setSession(null);
    setProfile(null);
    setRoleProfile(null);
  }, []);

  // A 401 anywhere in the app lands here: drop the session and flag it so the login
  // page can explain why the user is suddenly back at the door.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setSession(null);
      setProfile(null);
      setRoleProfile(null);
      setExpired(true);
    });
    return () => setUnauthorizedHandler(null);
  }, []);

  // Resolve the role-specific profile id. Neither CANDIDATE nor INTERVIEWER can list their
  // own kind (that's RECRUITER/ADMIN-only), so each resolves their own row via its own
  // GET .../me endpoint instead.
  const loadRoleProfile = useCallback(async (user) => {
    if (!user) return null;
    try {
      if (user.role === 'INTERVIEWER') {
        return await interviewersApi.mine();
      }
      if (user.role === 'CANDIDATE') {
        return await candidatesApi.mine();
      }
    } catch {
      // Not fatal — pages that need it show their own "no profile yet" state.
    }
    return null;
  }, []);

  // Rehydrate on refresh: the token survives in sessionStorage, the profile does not.
  useEffect(() => {
    let cancelled = false;
    async function bootstrap() {
      const stored = getStoredUser();
      if (!stored || !getToken()) {
        setBootstrapping(false);
        return;
      }
      try {
        const [me, rp] = await Promise.all([
          usersApi.get(stored.userId).catch(() => null),
          loadRoleProfile(stored),
        ]);
        if (cancelled) return;
        if (me) setProfile(me);
        setRoleProfile(rp);
      } finally {
        if (!cancelled) setBootstrapping(false);
      }
    }
    bootstrap();
    return () => {
      cancelled = true;
    };
  }, [loadRoleProfile]);

  const login = useCallback(
    async (email, password) => {
      const res = await authApi.login({ email, password });
      const user = { userId: res.userId, email: res.email, role: res.role };
      saveSession(res.token, user);
      setSession(user);
      setExpired(false);
      // Best-effort enrichment; a failure here must not block a successful login.
      const [me, rp] = await Promise.all([
        usersApi.get(user.userId).catch(() => null),
        loadRoleProfile(user),
      ]);
      if (me) setProfile(me);
      setRoleProfile(rp);
      return user;
    },
    [loadRoleProfile],
  );

  const register = useCallback((body) => authApi.register(body), []);

  const refreshProfile = useCallback(async () => {
    if (!session) return;
    const me = await usersApi.get(session.userId).catch(() => null);
    if (me) setProfile(me);
  }, [session]);

  const value = useMemo(
    () => ({
      session,
      profile,
      roleProfile,
      user: profile || session,
      role: session?.role || null,
      userId: session?.userId || null,
      name: profile?.name || session?.email || '',
      timezone: profile?.timezone || null,
      isAuthenticated: !!session,
      bootstrapping,
      expired,
      clearExpired: () => setExpired(false),
      login,
      logout,
      register,
      refreshProfile,
    }),
    [session, profile, roleProfile, bootstrapping, expired, login, logout, register, refreshProfile],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
