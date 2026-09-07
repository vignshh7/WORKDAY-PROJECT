import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { candidatesApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { useToast } from '../../context/ToastContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Button, Card, EmptyState, ErrorState, Field, InfoNote,
  Input, LoadingState, Modal, Select, Table,
} from '../../components/ui';
import { CandidateStatusBadge } from '../../components/StatusBadge';
import { CANDIDATE_STATUSES } from '../../constants/enums';
import { humanize, matchesQuery } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

export default function Candidates() {
  const navigate = useNavigate();
  const toast = useToast();
  const { data, error, loading, reload } = useFetch(() => candidatesApi.list(), []);
  const [query, setQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [creating, setCreating] = useState(false);

  const rows = (data || []).filter(
    (c) =>
      matchesQuery(c, query, ['name', 'email', 'phone']) &&
      (!statusFilter || c.currentStatus === statusFilter),
  );

  const columns = [
    {
      key: 'name',
      header: 'Candidate',
      render: (c) => (
        <div>
          <p className="font-medium text-slate-900">{c.name}</p>
          <p className="text-xs text-slate-500">{c.email}</p>
        </div>
      ),
    },
    { key: 'phone', header: 'Phone', render: (c) => c.phone || '—' },
    {
      key: 'currentStatus',
      header: 'Pipeline status',
      render: (c) => <CandidateStatusBadge status={c.currentStatus} />,
    },
    {
      key: 'resumeUrl',
      header: 'Resume',
      render: (c) =>
        c.resumeUrl ? (
          <a
            href={c.resumeUrl}
            target="_blank"
            rel="noreferrer"
            onClick={(e) => e.stopPropagation()}
            className="text-xs font-medium text-brand-700 hover:underline"
          >
            Open
          </a>
        ) : (
          <span className="text-xs text-slate-400">—</span>
        ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Candidates"
        subtitle="Everyone with a candidate profile, and where they are in the pipeline."
        actions={<Button onClick={() => setCreating(true)}>Add candidate profile</Button>}
      />

      <Card className="mb-4 flex flex-wrap gap-3 p-4">
        <Input
          placeholder="Search by name, email or phone…"
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
          {CANDIDATE_STATUSES.map((s) => (
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
            onRowClick={(c) => navigate(`/candidates/${c.id}`)}
            empty={
              <EmptyState
                icon="👤"
                title={data?.length ? 'No candidates match those filters' : 'No candidates yet'}
                detail={
                  data?.length
                    ? 'Clear the search or status filter to see everyone.'
                    : 'A candidate profile attaches to a user who already registered with the CANDIDATE role.'
                }
                className="m-4"
              />
            }
          />
        </Card>
      )}

      <CreateCandidateModal
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(c) => {
          toast.success(`Profile created for ${c.name}.`);
          setCreating(false);
          reload();
        }}
      />
    </>
  );
}

function CreateCandidateModal({ open, onClose, onCreated }) {
  const toast = useToast();
  const [form, setForm] = useState({ userId: '', phone: '', resumeUrl: '' });
  const [submitting, setSubmitting] = useState(false);

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const created = await candidatesApi.create({
        userId: form.userId.trim(),
        phone: form.phone.trim() || null,
        resumeUrl: form.resumeUrl.trim() || null,
      });
      setForm({ userId: '', phone: '', resumeUrl: '' });
      onCreated(created);
    } catch (err) {
      const parsed = parseApiError(err);
      toast.error(
        parsed.status === 404
          ? 'No user with that id. The person has to register with the CANDIDATE role first.'
          : parsed.message,
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Add a candidate profile"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button form="create-candidate" type="submit" loading={submitting}>
            Create profile
          </Button>
        </>
      }
    >
      <form id="create-candidate" onSubmit={submit} className="space-y-4">
        <InfoNote tone="info">
          This attaches a profile to a user who has <strong>already registered</strong> with the
          CANDIDATE role. It doesn&apos;t create the account — there&apos;s no endpoint that does
          both.
        </InfoNote>
        <Field label="User ID" required hint="The UUID of the registered candidate user.">
          <Input
            required
            value={form.userId}
            onChange={set('userId')}
            placeholder="00000000-0000-0000-0000-000000000000"
          />
        </Field>
        <Field label="Phone">
          <Input value={form.phone} onChange={set('phone')} placeholder="+91 98765 43210" />
        </Field>
        <Field label="Resume URL">
          <Input
            type="url"
            value={form.resumeUrl}
            onChange={set('resumeUrl')}
            placeholder="https://…"
          />
        </Field>
      </form>
    </Modal>
  );
}
