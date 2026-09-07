import { Link } from 'react-router-dom';
import { Button, Card } from '../components/ui';
import { useAuth } from '../auth/AuthContext';
import { ROLE_HOME } from '../routes/guards';

function ErrorPage({ code, title, detail, children }) {
  const { role } = useAuth();
  return (
    <div className="flex min-h-[60vh] items-center justify-center px-4">
      <Card className="max-w-md p-8 text-center">
        <p className="text-4xl font-bold text-slate-300">{code}</p>
        <h1 className="mt-2 text-lg font-semibold text-slate-900">{title}</h1>
        <p className="mt-2 text-sm text-slate-600">{detail}</p>
        {children}
        <Link to={ROLE_HOME[role] || '/'}>
          <Button variant="secondary" className="mt-5">
            Back to your dashboard
          </Button>
        </Link>
      </Card>
    </div>
  );
}

export function Forbidden() {
  return (
    <ErrorPage
      code="403"
      title="Not allowed"
      detail="Your role doesn't have access to this page, or this record belongs to someone else. Signing in again won't change that — ask an administrator if you think it should."
    />
  );
}

export function NotFound() {
  return (
    <ErrorPage
      code="404"
      title="Page not found"
      detail="That page doesn't exist. It may have been renamed, or the record it pointed at was removed."
    />
  );
}
