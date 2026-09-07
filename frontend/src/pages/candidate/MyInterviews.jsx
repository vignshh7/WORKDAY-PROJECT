import { Link } from 'react-router-dom';
import { candidatesApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Button, Card, CardBody, CardHeader, EmptyState, ErrorState, InfoNote, LoadingState,
} from '../../components/ui';
import { RoundSummary } from '../../components/Pipeline';
import { CandidateIdPrompt, useMyCandidateId } from './Dashboard';
import { isFuture } from '../../utils/datetime';
import { parseApiError } from '../../utils/errors';

export default function MyInterviews() {
  const { candidateId } = useMyCandidateId();
  const pipeline = useFetch(() => candidatesApi.pipeline(candidateId), [candidateId], {
    skip: !candidateId,
  });

  if (!candidateId) {
    return (
      <>
        <PageHeader title="My interviews" />
        <CandidateIdPrompt />
      </>
    );
  }

  if (pipeline.loading) return <LoadingState />;
  if (pipeline.error) {
    return <ErrorState error={parseApiError(pipeline.error)} onRetry={pipeline.reload} />;
  }

  const rounds = (pipeline.data || []).flatMap((e) => e.rounds || []);
  const upcoming = rounds
    .filter((r) => r.status === 'SCHEDULED' && isFuture(r.scheduledStart))
    .sort((a, b) => new Date(a.scheduledStart) - new Date(b.scheduledStart));
  const needsAction = rounds.filter((r) => r.status === 'RESCHEDULE_REQUIRED');
  const past = rounds
    .filter((r) => ['COMPLETED', 'CANCELLED'].includes(r.status))
    .sort((a, b) => new Date(b.scheduledStart || 0) - new Date(a.scheduledStart || 0));

  const openButton = (round) => (
    <Link to={`/interviews/${round.id}`}>
      <Button variant="secondary" size="sm">
        Open
      </Button>
    </Link>
  );

  return (
    <>
      <PageHeader title="My interviews" subtitle="Everything scheduled, and everything past." />

      <div className="space-y-5">
        {needsAction.length > 0 && (
          <Card>
            <CardHeader
              title="Needs a new time"
              subtitle="These rounds lost their slot — your pipeline stage is unchanged."
            />
            <CardBody className="space-y-2">
              {needsAction.map((r) => (
                <RoundSummary key={r.id} round={r} actions={openButton(r)} />
              ))}
            </CardBody>
          </Card>
        )}

        <Card>
          <CardHeader title="Upcoming" />
          <CardBody className="space-y-2">
            {upcoming.length === 0 ? (
              <EmptyState
                icon="📅"
                title="Nothing scheduled"
                detail="When a recruiter books a round, it appears here — and Google emails you the invitation with the Meet link."
              />
            ) : (
              upcoming.map((r) => <RoundSummary key={r.id} round={r} actions={openButton(r)} />)
            )}
          </CardBody>
        </Card>

        {upcoming.length > 0 && (
          <InfoNote tone="info" title="Where's the joining link?">
            Google sends the calendar invitation with the Meet link straight to your email. The
            backend doesn&apos;t return that link to this app, so it can&apos;t be shown here.
          </InfoNote>
        )}

        {past.length > 0 && (
          <Card>
            <CardHeader title="Past" />
            <CardBody className="space-y-2">
              {past.map((r) => (
                <RoundSummary key={r.id} round={r} actions={openButton(r)} />
              ))}
            </CardBody>
          </Card>
        )}
      </div>
    </>
  );
}
