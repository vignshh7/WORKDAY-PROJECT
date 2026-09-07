import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { LoadingState } from '../components/ui';

/** Home for each role — where "/" and a post-login redirect land. */
export const ROLE_HOME = {
  ADMIN: '/admin',
  RECRUITER: '/recruiter',
  INTERVIEWER: '/interviewer',
  CANDIDATE: '/candidate',
};

export function ProtectedRoute() {
  const { isAuthenticated, bootstrapping } = useAuth();
  const location = useLocation();

  // Don't bounce to login while the stored token is still being rehydrated.
  if (bootstrapping) return <LoadingState label="Restoring your session…" />;
  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}

/**
 * Role gate. Purely a navigation convenience — the backend enforces the same rule and
 * returns 403 on its own, so this never becomes the security boundary.
 */
export function RoleRoute({ allow }) {
  const { role } = useAuth();
  if (!allow.includes(role)) return <Navigate to="/forbidden" replace />;
  return <Outlet />;
}

export function HomeRedirect() {
  const { role, isAuthenticated, bootstrapping } = useAuth();
  if (bootstrapping) return <LoadingState />;
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return <Navigate to={ROLE_HOME[role] || '/settings/integrations'} replace />;
}
