import { Link } from 'react-router-dom';
import { interviewersApi, notificationsApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, CardHeader, DescriptionList,
  EmptyState, ErrorState, InfoNote, LoadingState,
} from '../../components/ui';
import { NOTIFICATION_META } from '../../constants/enums';
import { formatDateTime, relativeTime } from '../../utils/datetime';
import { parseApiError } from '../../utils/errors';

// Backend reality: there is no endpoint that lists an interviewer's assigned rounds,
// and InterviewRoundResponse carries no interviewerId, so "my interviews" can't be
// queried. What an interviewer *can* see is their own notifications, which reference
// the rounds they were assigned to by id. That's what this page is built on — an
// honest derivation, with the gap stated rather than hidden.

const ROUND_NOTIFICATION_TYPES = [
  'INTERVIEW_SCHEDULED', 'INTERVIEW_RESCHEDULED', 'INTERVIEW_CANCELLED',
  'INTERVIEWER_CANCELLED', 'INTERVIEWER_REPLACED', 'INTERVIEW_INVITATION_DECLINED',
  'INTERVIEW_REMINDER_24H', 'INTERVIEW_REMINDER_1H',
];

export default function InterviewerDashboard() {
  const { name, roleProfile } = useAuth();
  const notifications = useFetch(() => notificationsApi.list(), []);
  const skills = useFetch(() => interviewersApi.skills(roleProfile.id), [roleProfile?.id], {
    skip: !roleProfile?.id,
  });

  // Latest notification per round — each one is a round this interviewer touched.
  const rounds = [];
  const seen = new Set();
  for (const n of notifications.data || []) {
    if (!n.interviewRoundId || seen.has(n.interviewRoundId)) continue;
    if (!ROUND_NOTIFICATION_TYPES.includes(n.type)) continue;
    seen.add(n.interviewRoundId);
    rounds.push(n);
  }

  return (
    <>
      <PageHeader
        title={`Welcome, ${name}`}
        subtitle="Your interviews, availability and skills."
        actions={
          <Link to="/settings/integrations">
            <Button variant="secondary">Connect Google Calendar</Button>
          </Link>
        }
      />

      {!roleProfile && (
        <InfoNote tone="warning" className="mb-5" title="No interviewer profile yet">
          Your account has the interviewer role, but no interviewer profile is attached to it. A
          recruiter or admin has to create one before you can be matched to interviews.
        </InfoNote>
      )}

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          <Card>
            <CardHeader
              title="Your interviews"
              subtitle="Derived from your notifications — see the note below."
            />
            <CardBody className="space-y-3">
              {notifications.loading && <LoadingState />}
              {notifications.error && (
                <ErrorState
                  error={parseApiError(notifications.error)}
                  onRetry={notifications.reload}
                />
              )}
              {!notifications.loading && !notifications.error && (
                <>
                  {rounds.length === 0 ? (
                    <EmptyState
                      icon="📋"
                      title="No interviews yet"
                      detail="Once a recruiter books you for a round, it shows up here."
                    />
                  ) : (
                    rounds.map((n) => {
                      const meta = NOTIFICATION_META[n.type] || { icon: '•', label: n.type };
                      return (
                        <Link
                          key={n.interviewRoundId}
                          to={`/interviews/${n.interviewRoundId}`}
                          className="flex items-center justify-between rounded-lg border border-slate-200 px-4 py-3 hover:bg-slate-50"
                        >
                          <div className="flex min-w-0 items-center gap-3">
                            <span className="text-lg">{meta.icon}</span>
                            <div className="min-w-0">
                              <p className="text-sm font-medium text-slate-900">{meta.label}</p>
                              <p className="text-xs text-slate-500">
                                {formatDateTime(n.createdAt)} · {relativeTime(n.createdAt)}
                              </p>
                            </div>
                          </div>
                          <Badge tone="slate">Open</Badge>
                        </Link>
                      );
                    })
                  )}

                  <InfoNote tone="gap" title="Why this list looks like notifications">
                    The backend has no &ldquo;my assigned interviews&rdquo; endpoint, and a round
                    doesn&apos;t report who&apos;s assigned to it. This list is reconstructed from
                    the notifications you received, so it shows rounds you were involved with
                    rather than a live schedule. A dedicated endpoint would fix this.
                  </InfoNote>
                </>
              )}
            </CardBody>
          </Card>
        </div>

        <div className="space-y-5">
          <Card>
            <CardHeader title="Your profile" />
            <CardBody>
              {roleProfile ? (
                <DescriptionList
                  className="sm:grid-cols-1"
                  items={[
                    { label: 'Department', value: roleProfile.department || '—' },
                    { label: 'Designation', value: roleProfile.designation || '—' },
                    { label: 'Domain', value: roleProfile.domain || '—' },
                    {
                      label: 'Max interviews per day',
                      value: roleProfile.maxInterviewsPerDay ?? '—',
                    },
                  ]}
                />
              ) : (
                <p className="text-sm text-slate-500">No profile attached to your account yet.</p>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Your skills"
              subtitle="What you can be matched against."
            />
            <CardBody>
              {skills.loading && <LoadingState label="Loading skills…" />}
              {!skills.loading && (skills.data?.length ? (
                <ul className="space-y-2">
                  {skills.data.map((s) => (
                    <li
                      key={s.id}
                      className="flex items-center justify-between rounded-md border border-slate-200 px-3 py-2"
                    >
                      <span className="flex items-center gap-2 text-sm text-slate-800">
                        {s.skillName}
                        {s.isPrimary && <Badge tone="violet">primary</Badge>}
                      </span>
                      <Badge tone="blue">level {s.proficiency}</Badge>
                    </li>
                  ))}
                </ul>
              ) : (
                <EmptyState
                  icon="🎯"
                  title="No skills recorded"
                  detail="Without skills you won't match any round's requirements."
                />
              ))}
            </CardBody>
          </Card>

          <InfoNote tone="info" title="Set your availability once">
            Connect Google Calendar and the scheduler reads your real free/busy time — no manual
            availability needed. Without it, add windows on the availability page.
          </InfoNote>
        </div>
      </div>
    </>
  );
}
