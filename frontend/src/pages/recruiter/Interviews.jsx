import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAllRounds } from '../../hooks/useRounds';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Button, Card, EmptyState, ErrorState, Input, LoadingState, Select, Table,
} from '../../components/ui';
import { CandidateStatusBadge, ResultBadge, RoundStatusBadge } from '../../components/StatusBadge';
import { ROUND_STATUSES, ROUND_TYPES } from '../../constants/enums';
import { formatTimeRange, isFuture } from '../../utils/datetime';
import { humanize } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

const VIEWS = [
  { key: 'upcoming', label: 'Upcoming' },
  { key: 'attention', label: 'Needs attention' },
  { key: 'all', label: 'All' },
];

export default function Interviews() {
  const navigate = useNavigate();
  const { data, error, loading, reload } = useAllRounds();
  const [view, setView] = useState('upcoming');
  const [query, setQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState('');

  if (loading) return <LoadingState label="Assembling interviews from every pipeline…" />;
  if (error) return <ErrorState error={parseApiError(error)} onRetry={reload} />;

  const all = data?.rounds || [];
  const byView = {
    upcoming: all.filter((r) => r.status === 'SCHEDULED' && isFuture(r.scheduledStart)),
    attention: all.filter((r) =>
      ['RESCHEDULE_REQUIRED', 'SCHEDULING', 'PENDING'].includes(r.status),
    ),
    all,
  }[view];

  const rows = byView.filter(
    (r) =>
      (!query || r.candidate.name.toLowerCase().includes(query.trim().toLowerCase())) &&
      (!statusFilter || r.status === statusFilter) &&
      (!typeFilter || r.roundType === typeFilter),
  );

  const columns = [
    {
      key: 'candidate',
      header: 'Candidate',
      render: (r) => (
        <div>
          <p className="font-medium text-slate-900">{r.candidate.name}</p>
          <p className="text-xs text-slate-500">{r.candidate.email}</p>
        </div>
      ),
    },
    {
      key: 'round',
      header: 'Round',
      render: (r) => (
        <span className="text-sm text-slate-700">
          {r.roundNumber}. {humanize(r.roundType)}
        </span>
      ),
    },
    {
      key: 'when',
      header: 'When',
      render: (r) =>
        r.scheduledStart ? (
          <span className="text-sm text-slate-700">
            {formatTimeRange(r.scheduledStart, r.scheduledEnd, r.timezone)}
          </span>
        ) : (
          <span className="text-xs text-slate-400">Not scheduled</span>
        ),
    },
    {
      key: 'status',
      header: 'Round status',
      render: (r) => (
        <span className="flex flex-wrap items-center gap-1.5">
          <RoundStatusBadge status={r.status} />
          <ResultBadge result={r.result} hidePending />
        </span>
      ),
    },
    {
      key: 'candidateStatus',
      header: 'Pipeline',
      render: (r) => <CandidateStatusBadge status={r.candidate.currentStatus} />,
    },
  ];

  return (
    <>
      <PageHeader
        title="Interviews"
        subtitle="Every round across every candidate."
        actions={
          <Link to="/recruiter/scheduling">
            <Button>Schedule an interview</Button>
          </Link>
        }
      />

      <Card className="mb-4 flex flex-wrap items-center gap-3 p-4">
        <div className="inline-flex rounded-md border border-slate-300 bg-white p-0.5">
          {VIEWS.map((v) => (
            <button
              key={v.key}
              type="button"
              onClick={() => setView(v.key)}
              className={`rounded px-3 py-1.5 text-xs font-medium ${
                view === v.key ? 'bg-brand-600 text-white' : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              {v.label}
            </button>
          ))}
        </div>
        <Input
          placeholder="Search candidate…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="max-w-xs"
        />
        <Select
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value)}
          className="max-w-[10rem]"
        >
          <option value="">All rounds</option>
          {ROUND_TYPES.map((t) => (
            <option key={t} value={t}>
              {humanize(t)}
            </option>
          ))}
        </Select>
        <Select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          className="max-w-[12rem]"
        >
          <option value="">All statuses</option>
          {ROUND_STATUSES.map((s) => (
            <option key={s} value={s}>
              {humanize(s)}
            </option>
          ))}
        </Select>
      </Card>

      <Card>
        <Table
          columns={columns}
          rows={rows}
          onRowClick={(r) => navigate(`/interviews/${r.id}`)}
          empty={
            <EmptyState
              icon="📅"
              title={
                view === 'upcoming'
                  ? 'Nothing scheduled ahead'
                  : view === 'attention'
                    ? 'Nothing needs attention'
                    : 'No interviews yet'
              }
              detail={
                all.length
                  ? 'Try another view or clear the filters.'
                  : 'Start an interview process for a candidate, then book a round.'
              }
              className="m-4"
            />
          }
        />
      </Card>
    </>
  );
}
