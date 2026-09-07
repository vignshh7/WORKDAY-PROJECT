import { Link } from 'react-router-dom';
import { auditApi, integrationsApi, usersApi } from '../../api/endpoints';
import { useAllRounds } from '../../hooks/useRounds';
import { useFetch } from '../../hooks/useAsync';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, CardHeader, ErrorState,
  InfoNote, LoadingState, StatTile,
} from '../../components/ui';
import { CountBars } from '../recruiter/Overview';
import { formatDateTime } from '../../utils/datetime';
import { countBy, shortId } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

// Phase 25 (admin analytics + scheduling-config endpoints) was skipped on the backend.
// Panels that CAN be honestly computed from real data are computed client-side; the
// ones with no backing data at all are named as unavailable rather than invented.

const UNAVAILABLE_PANELS = [
  ['Interviewer utilization', 'Needs booked-hours-per-interviewer aggregation; no endpoint exposes it.'],
  ['Scheduling success rate', 'Needs attempted-vs-booked counts, which are never persisted.'],
  ['Conflicts over time', 'Conflict checks are computed per request and not stored.'],
  ['Calendar sync failures', 'Booking succeeds even when calendar sync fails, and no field records it.'],
];

export default function AdminOverview() {
  const users = useFetch(() => usersApi.list(), []);
  const rounds = useAllRounds();
  const audit = useFetch(() => auditApi.list({ limit: 8 }), []);
  const google = useFetch(() => integrationsApi.status(), []);

  if (users.loading) return <LoadingState label="Loading administration data…" />;
  if (users.error) return <ErrorState error={parseApiError(users.error)} onRetry={users.reload} />;

  const allUsers = users.data || [];
  const allRounds = rounds.data?.rounds || [];
  const candidates = rounds.data?.candidates || [];

  return (
    <>
      <PageHeader
        title="Administration"
        subtitle="Users, audit activity, and what the platform can and can't report on."
        actions={
          <>
            <Link to="/admin/users">
              <Button variant="secondary">Users</Button>
            </Link>
            <Link to="/admin/audit-logs">
              <Button>Audit logs</Button>
            </Link>
          </>
        }
      />

      <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label="Users" value={allUsers.length} />
        <StatTile
          label="Active"
          value={allUsers.filter((u) => u.status === 'ACTIVE').length}
          tone="green"
        />
        <StatTile
          label="Suspended"
          value={allUsers.filter((u) => u.status === 'SUSPENDED').length}
          tone={allUsers.some((u) => u.status === 'SUSPENDED') ? 'red' : 'slate'}
        />
        <StatTile label="Candidates in pipeline" value={candidates.length} />
      </div>

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          <div className="grid gap-5 sm:grid-cols-2">
            <Card>
              <CardHeader title="Users by role" />
              <CardBody>
                <CountBars counts={countBy(allUsers, 'role')} />
              </CardBody>
            </Card>
            <Card>
              <CardHeader title="Users by status" />
              <CardBody>
                <CountBars counts={countBy(allUsers, 'status')} tone="bg-green-500" />
              </CardBody>
            </Card>
          </div>

          <div className="grid gap-5 sm:grid-cols-2">
            <Card>
              <CardHeader
                title="Candidate stages"
                subtitle="Counted from the candidate list."
              />
              <CardBody>
                {rounds.loading ? (
                  <LoadingState label="Counting…" />
                ) : (
                  <CountBars counts={countBy(candidates, 'currentStatus')} tone="bg-indigo-500" />
                )}
              </CardBody>
            </Card>
            <Card>
              <CardHeader title="Interview status" subtitle="Counted from every pipeline." />
              <CardBody>
                {rounds.loading ? (
                  <LoadingState label="Counting…" />
                ) : (
                  <CountBars counts={countBy(allRounds, 'status')} tone="bg-violet-500" />
                )}
              </CardBody>
            </Card>
          </div>

          <Card>
            <CardHeader
              title="Rescheduling"
              subtitle="Rounds that have been rescheduled at least once."
            />
            <CardBody>
              {rounds.loading ? (
                <LoadingState label="Counting…" />
              ) : (
                <CountBars
                  counts={countBy(
                    allRounds.filter((r) => (r.rescheduleCount ?? 0) > 0),
                    (r) => `${r.rescheduleCount}×`,
                  )}
                  tone="bg-amber-500"
                  emptyLabel="Nothing has been rescheduled yet."
                />
              )}
            </CardBody>
          </Card>
        </div>

        <div className="space-y-5">
          <Card>
            <CardHeader
              title="Recent audit activity"
              action={
                <Link to="/admin/audit-logs" className="text-xs font-medium text-brand-700 hover:underline">
                  View all
                </Link>
              }
            />
            <CardBody>
              {audit.loading && <LoadingState label="Loading…" />}
              {audit.error && <p className="text-xs text-slate-500">Couldn&apos;t load audit entries.</p>}
              {!audit.loading && !audit.error && (
                <ul className="space-y-2">
                  {(audit.data || []).map((log) => (
                    <li key={log.id} className="text-xs">
                      <p className="font-mono font-medium text-slate-800">{log.action}</p>
                      <p className="text-slate-500">
                        {log.actorType === 'SYSTEM' ? 'System' : shortId(log.actorId)} ·{' '}
                        {formatDateTime(log.createdAt)}
                      </p>
                    </li>
                  ))}
                  {(audit.data || []).length === 0 && (
                    <p className="text-xs text-slate-500">No activity recorded yet.</p>
                  )}
                </ul>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Integration health"
              action={
                google.data?.activeProvider === 'google' ? (
                  <Badge tone="green">Google</Badge>
                ) : (
                  <Badge tone="slate">{google.data?.activeProvider || 'unknown'}</Badge>
                )
              }
            />
            <CardBody className="space-y-3 text-sm text-slate-600">
              <p>
                Calendar provider:{' '}
                <strong>{google.data?.activeProvider || 'unknown'}</strong>
                {google.data?.activeProvider !== 'google' &&
                  ' — calendar operations are no-ops in this mode.'}
              </p>
              <p>
                Your own connection:{' '}
                <strong>{google.data?.connected ? 'connected' : 'not connected'}</strong>
              </p>
              <InfoNote tone="gap">
                There&apos;s no admin-wide view of who has connected their calendar — the status
                endpoint only reports the caller&apos;s own connection. This panel is therefore
                about your account, not the organization.
              </InfoNote>
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Not available"
              subtitle="Reports with no backing data on the backend."
            />
            <CardBody>
              <ul className="space-y-3">
                {UNAVAILABLE_PANELS.map(([title, why]) => (
                  <li key={title}>
                    <p className="text-sm font-medium text-slate-700">{title}</p>
                    <p className="text-xs text-slate-500">{why}</p>
                  </li>
                ))}
              </ul>
              <InfoNote tone="gap" className="mt-4">
                Scheduling configuration — working hours, buffers, notice period, maximum
                reschedules, replacement policy — is read by the engine but has no management
                endpoint. Changing it means a direct database update today.
              </InfoNote>
            </CardBody>
          </Card>
        </div>
      </div>
    </>
  );
}
