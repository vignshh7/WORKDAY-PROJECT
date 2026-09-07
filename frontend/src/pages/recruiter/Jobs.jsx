import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { jobsApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { useToast } from '../../context/ToastContext';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Button, Card, EmptyState, ErrorState, Field, Input,
  LoadingState, Modal, Select, Table, Textarea,
} from '../../components/ui';
import { JobStatusBadge } from '../../components/StatusBadge';
import { JOB_STATUSES } from '../../constants/enums';
import { humanize, matchesQuery } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

export default function Jobs() {
  const navigate = useNavigate();
  const toast = useToast();
  const { role } = useAuth();
  const isStaff = role === 'RECRUITER' || role === 'ADMIN';
  const { data, error, loading, reload } = useFetch(() => jobsApi.list(), []);
  const [query, setQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [creating, setCreating] = useState(false);

  const rows = (data || []).filter(
    (j) =>
      matchesQuery(j, query, ['title', 'department', 'domain']) &&
      (!statusFilter || j.status === statusFilter),
  );

  const columns = [
    {
      key: 'title',
      header: 'Job',
      render: (j) => (
        <div>
          <p className="font-medium text-slate-900">{j.title}</p>
          <p className="line-clamp-1 text-xs text-slate-500">{j.description}</p>
        </div>
      ),
    },
    { key: 'department', header: 'Department', render: (j) => j.department || '—' },
    { key: 'domain', header: 'Domain', render: (j) => j.domain || '—' },
    { key: 'status', header: 'Status', render: (j) => <JobStatusBadge status={j.status} /> },
  ];

  return (
    <>
      <PageHeader
        title="Jobs"
        subtitle="Roles candidates are being interviewed for."
        actions={isStaff && <Button onClick={() => setCreating(true)}>Create job</Button>}
      />

      <Card className="mb-4 flex flex-wrap gap-3 p-4">
        <Input
          placeholder="Search by title, department or domain…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="max-w-xs"
        />
        <Select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          className="max-w-[12rem]"
        >
          <option value="">All statuses</option>
          {JOB_STATUSES.map((s) => (
            <option key={s} value={s}>
              {humanize(s)}
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
            onRowClick={(j) => navigate(`/jobs/${j.id}`)}
            empty={
              <EmptyState
                icon="💼"
                title={data?.length ? 'No jobs match those filters' : 'No jobs yet'}
                detail={
                  data?.length
                    ? 'Clear the filters to see everything.'
                    : 'A new job starts as a draft. Add its required skills, then open it.'
                }
                className="m-4"
              />
            }
          />
        </Card>
      )}

      <CreateJobModal
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(job) => {
          setCreating(false);
          toast.success(`"${job.title}" created as a draft.`);
          navigate(`/jobs/${job.id}`);
        }}
      />
    </>
  );
}

function CreateJobModal({ open, onClose, onCreated }) {
  const toast = useToast();
  const [form, setForm] = useState({ title: '', description: '', department: '', domain: '' });
  const [submitting, setSubmitting] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const job = await jobsApi.create({
        title: form.title.trim(),
        description: form.description.trim(),
        department: form.department.trim(),
        domain: form.domain.trim(),
      });
      setForm({ title: '', description: '', department: '', domain: '' });
      onCreated(job);
    } catch (err) {
      toast.error(parseApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Create a job"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button form="create-job" type="submit" loading={submitting}>
            Create job
          </Button>
        </>
      }
    >
      <form id="create-job" onSubmit={submit} className="space-y-4">
        <Field label="Title" required>
          <Input required value={form.title} onChange={set('title')} placeholder="Senior Java Engineer" />
        </Field>
        <Field label="Description">
          <Textarea rows={3} value={form.description} onChange={set('description')} />
        </Field>
        <div className="grid grid-cols-2 gap-4">
          <Field label="Department" required>
            <Input required value={form.department} onChange={set('department')} placeholder="Engineering" />
          </Field>
          <Field
            label="Domain"
            required
            hint="Used as a ranking signal when matching interviewers."
          >
            <Input required value={form.domain} onChange={set('domain')} placeholder="Backend" />
          </Field>
        </div>
      </form>
    </Modal>
  );
}
