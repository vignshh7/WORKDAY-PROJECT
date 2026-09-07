import { useState } from 'react';
import { interviewersApi, usersApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { useToast } from '../../context/ToastContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Button, Card, EmptyState, ErrorState, Field, InfoNote, Input,
  LoadingState, Modal, Select, Table,
} from '../../components/ui';
import { RoleBadge, UserStatusBadge } from '../../components/StatusBadge';
import { ROLES, USER_STATUSES } from '../../constants/enums';
import { formatDate } from '../../utils/datetime';
import { humanize, matchesQuery } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

export default function Users() {
  const toast = useToast();
  const { data, error, loading, reload } = useFetch(() => usersApi.list(), []);
  const [query, setQuery] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const [statusTarget, setStatusTarget] = useState(null);
  const [addingInterviewer, setAddingInterviewer] = useState(null);

  const rows = (data || []).filter(
    (u) => matchesQuery(u, query, ['name', 'email']) && (!roleFilter || u.role === roleFilter),
  );

  const setStatus = async (user, status) => {
    try {
      await usersApi.setStatus(user.id, status);
      toast.success(`${user.name} is now ${status.toLowerCase()}.`);
      setStatusTarget(null);
      reload();
    } catch (err) {
      toast.apiError(err);
    }
  };

  const columns = [
    {
      key: 'name',
      header: 'User',
      render: (u) => (
        <div>
          <p className="font-medium text-slate-900">{u.name}</p>
          <p className="text-xs text-slate-500">{u.email}</p>
        </div>
      ),
    },
    { key: 'role', header: 'Role', render: (u) => <RoleBadge role={u.role} /> },
    { key: 'status', header: 'Status', render: (u) => <UserStatusBadge status={u.status} /> },
    { key: 'timezone', header: 'Timezone', render: (u) => u.timezone || '—' },
    { key: 'createdAt', header: 'Joined', render: (u) => formatDate(u.createdAt) },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      render: (u) => (
        <span className="flex justify-end gap-2">
          {u.role === 'INTERVIEWER' && (
            <Button variant="ghost" size="sm" onClick={() => setAddingInterviewer(u)}>
              Add profile
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={() => setStatusTarget(u)}>
            Change status
          </Button>
        </span>
      ),
    },
  ];

  return (
    <>
      <PageHeader title="Users" subtitle="Everyone registered, across every role." />

      <Card className="mb-4 flex flex-wrap gap-3 p-4">
        <Input
          placeholder="Search by name or email…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="max-w-xs"
        />
        <Select
          value={roleFilter}
          onChange={(e) => setRoleFilter(e.target.value)}
          className="max-w-[12rem]"
        >
          <option value="">All roles</option>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {humanize(r)}
            </option>
          ))}
        </Select>
      </Card>

      {loading && <LoadingState />}
      {error && <ErrorState error={parseApiError(error)} onRetry={reload} />}

      {!loading && !error && (
        <Card>
          <Table
            columns={columns}
            rows={rows}
            empty={
              <EmptyState
                icon="👥"
                title="No users match those filters"
                className="m-4"
              />
            }
          />
        </Card>
      )}

      <StatusModal
        user={statusTarget}
        onClose={() => setStatusTarget(null)}
        onSave={setStatus}
      />

      <InterviewerProfileModal
        user={addingInterviewer}
        onClose={() => setAddingInterviewer(null)}
        onCreated={() => {
          setAddingInterviewer(null);
          toast.success('Interviewer profile created.');
        }}
      />
    </>
  );
}

function StatusModal({ user, onClose, onSave }) {
  const [status, setStatus] = useState('ACTIVE');
  const [loadedFor, setLoadedFor] = useState(null);

  if (user && loadedFor !== user.id) {
    setLoadedFor(user.id);
    setStatus(user.status);
  }

  return (
    <Modal
      open={!!user}
      title="Change account status"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => onSave(user, status)}>Save status</Button>
        </>
      }
    >
      {user && (
        <div className="space-y-4">
          <p className="text-sm text-slate-700">
            <strong>{user.name}</strong> · {user.email}
          </p>
          <Field label="Status">
            <Select value={status} onChange={(e) => setStatus(e.target.value)}>
              {USER_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {humanize(s)}
                </option>
              ))}
            </Select>
          </Field>
          <InfoNote tone="warning">
            Only <strong>active</strong> interviewers qualify for matching. Suspending someone
            removes them from every future slot search.
          </InfoNote>
        </div>
      )}
    </Modal>
  );
}

function InterviewerProfileModal({ user, onClose, onCreated }) {
  const toast = useToast();
  const [form, setForm] = useState({
    department: '', designation: '', domain: '', maxInterviewsPerDay: 3,
  });
  const [submitting, setSubmitting] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await interviewersApi.create({
        userId: user.id,
        department: form.department.trim(),
        designation: form.designation.trim(),
        domain: form.domain.trim(),
        maxInterviewsPerDay: Number(form.maxInterviewsPerDay),
      });
      setForm({ department: '', designation: '', domain: '', maxInterviewsPerDay: 3 });
      onCreated();
    } catch (err) {
      const parsed = parseApiError(err);
      toast.error(
        parsed.status === 409 ? 'This user already has an interviewer profile.' : parsed.message,
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={!!user}
      title="Create an interviewer profile"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button form="interviewer-profile" type="submit" loading={submitting}>
            Create profile
          </Button>
        </>
      }
    >
      {user && (
        <form id="interviewer-profile" onSubmit={submit} className="space-y-4">
          <p className="text-sm text-slate-700">
            For <strong>{user.name}</strong> · {user.email}
          </p>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Department" required>
              <Input required value={form.department} onChange={set('department')} placeholder="Engineering" />
            </Field>
            <Field label="Designation" required>
              <Input required value={form.designation} onChange={set('designation')} placeholder="Staff Engineer" />
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Domain" required hint="Ranking signal">
              <Input required value={form.domain} onChange={set('domain')} placeholder="Backend" />
            </Field>
            <Field label="Max interviews / day" required>
              <Input
                type="number"
                min={1}
                required
                value={form.maxInterviewsPerDay}
                onChange={set('maxInterviewsPerDay')}
              />
            </Field>
          </div>
          <InfoNote tone="info">
            Skills are added separately, from the interviewer&apos;s own profile page. Without
            skills they won&apos;t match any round.
          </InfoNote>
        </form>
      )}
    </Modal>
  );
}
