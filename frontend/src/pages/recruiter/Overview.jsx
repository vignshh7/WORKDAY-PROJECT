import { Link } from 'react-router-dom';
import { jobsApi } from '../../api/endpoints';
import { useAllRounds } from '../../hooks/useRounds';
import { useFetch } from '../../hooks/useAsync';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Button, Card, CardBody, CardHeader, EmptyState, ErrorState,
  InfoNote, LoadingState, StatTile,
} from '../../components/ui';
import { RoundStatusBadge } from '../../components/StatusBadge';
import { formatTimeRange, isFuture } from '../../utils/datetime';
import { countBy, humanize } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

/** A count bar chart. All counts are derived client-side from data already fetched —
 *  the backend has no analytics endpoint, so nothing here is a fabricated metric. */
export function CountBars({ counts, tone = 'bg-brand-500', emptyLabel = 'No data yet' }) {
  const entries = Object.entries(counts).sort((a, b) => b[1] - a[1]);
  const max = Math.max(1, ...entries.map(([, n]) => n));
  if (!entries.length) return <p className="text-xs text-slate-500">{emptyLabel}</p>;
  return (
    <ul className="space-y-2">
      {entries.map(([label, n]) => (
        <li key={label} className="flex items-center gap-3">
          <span className="w-32 shrink-0 truncate text-xs text-slate-600">{humanize(label)}</span>
          <span className="h-2 flex-1 overflow-hidden rounded-full bg-slate-100">
            <span
              className={`block h-full rounded-full ${tone}`}
              style={{ width: `${(n / max) * 100}%` }}
            />
          </span>
          <span className="w-6 shrink-0 text-right text-xs font-medium text-slate-700">{n}</span>
        </li>
      ))}
    </ul>
  );
}

export default function RecruiterOverview() {
  const { data, error, loading, reload } = useAllRounds();
  const jobs = useFetch(() => jobsApi.list(), []);

  if (loading) return <LoadingState label="Loading your pipeline…" />;
  if (error) return <ErrorState error={parseApiError(error)} onRetry={reload} />;

  const { candidates = [], rounds = [] } = data || {};
  const upcoming = rounds.filter((r) => r.status === 'SCHEDULED' && isFuture(r.scheduledStart));
  const needsAttention = rounds.filter((r) => r.status === 'RESCHEDULE_REQUIRED');
  const unscheduled = rounds.filter((r) => r.status === 'PENDING');
  const openJobs = (jobs.data || []).filter((j) => j.status === 'OPEN');

  return (
    <>
      <PageHeader
        title="Overview"
        subtitle="Everything currently moving through your pipeline."
        actions={
          <Link to="/recruiter/scheduling">
            <Button>Schedule an interview</Button>
          </Link>
        }
      />

      <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label="Candidates" value={candidates.length} />
        <StatTile label="Upcoming interviews" value={upcoming.length} tone="blue" />
        <StatTile
          label="Need rescheduling"
          value={needsAttention.length}
          tone={needsAttention.length ? 'amber' : 'slate'}
          hint={needsAttention.length ? 'Cancelled by an interviewer or a participant' : undefined}
        />
        <StatTile label="Open jobs" value={openJobs.length} hint={`${(jobs.data || []).length} total`} />
      </div>

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          {needsAttention.length > 0 && (
            <Card>
              <CardHeader
                title="Needs rescheduling"
                subtitle="These rounds lost their slot and are waiting on a new one."
              />
              <CardBody className="space-y-2">
                {needsAttention.map((r) => (
                  <Link
                    key={r.id}
                    to={`/interviews/${r.id}`}
                    className="flex items-center justify-between rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 hover:bg-amber-100"
                  >
                    <div>
                      <p className="text-sm font-medium text-slate-900">{r.candidate.name}</p>
                      <p className="text-xs text-slate-600">
                        Round {r.roundNumber} · {humanize(r.roundType)}
                        {r.rescheduleCount > 0 && ` · rescheduled ${r.rescheduleCount}×`}
                      </p>
                    </div>
                    <RoundStatusBadge status={r.status} />
                  </Link>
                ))}
              </CardBody>
            </Card>
          )}

          <Card>
            <CardHeader
              title="Upcoming interviews"
              action={
                <Link to="/recruiter/interviews" className="text-xs font-medium text-brand-700 hover:underline">
                  View all
                </Link>
              }
            />
            <CardBody>
              {upcoming.length === 0 ? (
                <EmptyState
                  icon="📅"
                  title="Nothing scheduled"
                  detail="Book a round from the scheduling page to see it here."
                  action={
                    <Link to="/recruiter/scheduling">
                      <Button size="sm">Schedule an interview</Button>
                    </Link>
                  }
                />
              ) : (
                <div className="space-y-2">
                  {upcoming.slice(0, 6).map((r) => (
                    <Link
                      key={r.id}
                      to={`/interviews/${r.id}`}
                      className="flex items-center justify-between rounded-lg border border-slate-200 px-4 py-3 hover:bg-slate-50"
                    >
                      <div>
                        <p className="text-sm font-medium text-slate-900">{r.candidate.name}</p>
                        <p className="text-xs text-slate-600">
                          {humanize(r.roundType)} ·{' '}
                          {formatTimeRange(r.scheduledStart, r.scheduledEnd, r.timezone)}
                        </p>
                      </div>
                      <RoundStatusBadge status={r.status} />
                    </Link>
                  ))}
                </div>
              )}
            </CardBody>
          </Card>
        </div>

        <div className="space-y-5">
          <Card>
            <CardHeader
              title="Candidates by stage"
              subtitle="Counted from the candidate list, not a backend metric."
            />
            <CardBody>
              <CountBars counts={countBy(candidates, 'currentStatus')} />
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Rounds by status" />
            <CardBody>
              <CountBars counts={countBy(rounds, 'status')} tone="bg-indigo-500" />
            </CardBody>
          </Card>

          {unscheduled.length > 0 && (
            <InfoNote tone="info" title={`${unscheduled.length} rounds not scheduled yet`}>
              A round stays <strong>Pending</strong> until it&apos;s booked. Earlier rounds in the
              same process have to finish first.
            </InfoNote>
          )}
        </div>
      </div>
    </>
  );
}
