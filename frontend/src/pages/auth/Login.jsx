import { useEffect, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { useToast } from '../../context/ToastContext';
import { Button, Card, Field, Input } from '../../components/ui';
import { ROLE_HOME } from '../../routes/guards';
import { parseApiError } from '../../utils/errors';

export default function Login() {
  const { login, isAuthenticated, role, expired, clearExpired } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();
  const [form, setForm] = useState({ email: '', password: '' });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  // A 401 anywhere in the app redirects here; say why rather than silently resetting.
  useEffect(() => {
    if (expired) {
      toast.warning('Your session expired. Please sign in again.');
      clearExpired();
    }
  }, [expired, toast, clearExpired]);

  if (isAuthenticated) {
    return <Navigate to={location.state?.from || ROLE_HOME[role] || '/'} replace />;
  }

  const onSubmit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const user = await login(form.email.trim(), form.password);
      toast.success(`Signed in as ${user.email}`);
      navigate(location.state?.from || ROLE_HOME[user.role] || '/', { replace: true });
    } catch (err) {
      const parsed = parseApiError(err);
      setError(
        parsed.status === 401 || parsed.status === 403
          ? 'Email or password is incorrect.'
          : parsed.message,
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthShell
      title="Sign in"
      footer={
        <>
          Don&apos;t have an account?{' '}
          <Link to="/register" className="font-medium text-brand-700 hover:underline">
            Create one
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email" required>
          <Input
            type="email"
            required
            autoComplete="email"
            value={form.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}
            placeholder="you@example.com"
          />
        </Field>
        <Field label="Password" required>
          <Input
            type="password"
            required
            autoComplete="current-password"
            value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
          />
        </Field>
        {error && (
          <p className="rounded-md bg-red-50 px-3 py-2 text-xs text-red-800">{error}</p>
        )}
        <Button type="submit" loading={submitting} className="w-full">
          Sign in
        </Button>
      </form>
    </AuthShell>
  );
}

export function AuthShell({ title, children, footer }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-50 px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 flex items-center justify-center gap-2">
          <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-600 text-base font-bold text-white">
            IS
          </span>
          <span className="text-lg font-semibold text-slate-900">Interview Scheduler</span>
        </div>
        <Card className="p-6">
          <h1 className="mb-5 text-lg font-semibold text-slate-900">{title}</h1>
          {children}
        </Card>
        {footer && <p className="mt-4 text-center text-sm text-slate-600">{footer}</p>}
      </div>
    </div>
  );
}
