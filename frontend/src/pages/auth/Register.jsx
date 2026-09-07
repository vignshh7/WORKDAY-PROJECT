import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { useToast } from '../../context/ToastContext';
import { Button, Field, InfoNote, Input, Select } from '../../components/ui';
import { AuthShell } from './Login';
import { SIGNUP_ROLES } from '../../constants/enums';
import { browserTimezone, timezoneOptions } from '../../utils/datetime';
import { humanize } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

export default function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const [form, setForm] = useState({
    name: '',
    email: '',
    password: '',
    role: 'RECRUITER',
    timezone: browserTimezone(),
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));

  const onSubmit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await register({ ...form, name: form.name.trim(), email: form.email.trim() });
      toast.success('Account created. Sign in to continue.');
      navigate('/login', { replace: true });
    } catch (err) {
      setError(parseApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthShell
      title="Create an account"
      footer={
        <>
          Already registered?{' '}
          <Link to="/login" className="font-medium text-brand-700 hover:underline">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Full name" required>
          <Input required value={form.name} onChange={set('name')} placeholder="Jane Recruiter" />
        </Field>
        <Field label="Email" required>
          <Input
            type="email"
            required
            autoComplete="email"
            value={form.email}
            onChange={set('email')}
            placeholder="you@example.com"
          />
        </Field>
        <Field label="Password" required hint="At least 8 characters.">
          <Input
            type="password"
            required
            minLength={8}
            autoComplete="new-password"
            value={form.password}
            onChange={set('password')}
          />
        </Field>
        <Field label="Role" required>
          <Select value={form.role} onChange={set('role')}>
            {SIGNUP_ROLES.map((r) => (
              <option key={r} value={r}>
                {humanize(r)}
              </option>
            ))}
          </Select>
        </Field>
        <Field
          label="Timezone"
          required
          hint="Used to map the organization's working hours onto your day."
        >
          <Select value={form.timezone} onChange={set('timezone')}>
            {timezoneOptions().map((tz) => (
              <option key={tz} value={tz}>
                {tz}
              </option>
            ))}
          </Select>
        </Field>

        {/* The backend does not gate ADMIN registration, so the picker does. */}
        <InfoNote tone="gap">
          Administrator accounts aren&apos;t self-service here. The backend does not gate
          admin signup itself, so this form deliberately omits the role.
        </InfoNote>

        {error && <p className="rounded-md bg-red-50 px-3 py-2 text-xs text-red-800">{error}</p>}
        <Button type="submit" loading={submitting} className="w-full">
          Create account
        </Button>
      </form>
    </AuthShell>
  );
}
