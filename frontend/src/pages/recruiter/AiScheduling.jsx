import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { aiApi } from '../../api/endpoints';
import { useInterviewerNames } from '../../hooks/useRounds';
import { useToast } from '../../context/ToastContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState,
  InfoNote, LoadingState, Modal, Textarea,
} from '../../components/ui';
import { CandidateStatusBadge, RoundStatusBadge } from '../../components/StatusBadge';
import { InterviewerMatchList, SchedulingResults } from '../../components/SchedulingResults';
import { AI_ACTION_LABELS } from '../../constants/enums';
import { formatTimeRange } from '../../utils/datetime';
import { createIdempotencyHolder } from '../../utils/idempotency';
import { humanize } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

const EXAMPLES = [
  'Schedule Rahul’s Java technical interview next week for 60 minutes, prefer afternoon and exclude Friday.',
  'Find a managerial round for Priya in the first week of next month.',
  'What is Rahul’s current status?',
];

/**
 * Natural language in, a *proposal* out. /schedule never writes anything; /confirm
 * executes the exact proposal through the same services the REST endpoints use.
 */
export function AiPanel() {
  const toast = useToast();
  const navigate = useNavigate();
  const { namesById } = useInterviewerNames();

  const [message, setMessage] = useState('');
  const [thinking, setThinking] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [outcome, setOutcome] = useState(null);
  const idempotency = useRef(createIdempotencyHolder());

  const ask = async (text) => {
    const msg = (text ?? message).trim();
    if (!msg) return;
    setThinking(true);
    setError(null);
    setResult(null);
    setOutcome(null);
    idempotency.current.reset();
    try {
      const res = await aiApi.schedule(msg);
      setResult(res);
      // AUTO_SWITCH_IF_QUALIFIED: the backend already executed the switch, so there is
      // nothing left to confirm — present it as done, not as a pending proposal.
      if (res.action && !res.confirmationRequired) {
        setOutcome({ alreadyApplied: true, message: res.reason });
      }
    } catch (err) {
      const parsed = parseApiError(err);
      setError(err);
      if (parsed.status === 500) {
        toast.error(
          'The AI service is unavailable — the server may not have an AI_API_KEY configured. The form mode works without it.',
          { title: 'AI unavailable' },
        );
      } else {
        toast.apiError(err);
      }
    } finally {
      setThinking(false);
    }
  };

  const confirm = async () => {
    if (!result?.action) return;
    setConfirming(true);
    try {
      // The action goes back byte-for-byte. It can carry fields this UI never renders,
      // so it is never reconstructed from what's on screen.
      const res = await aiApi.confirm(result.action, idempotency.current.key());
      idempotency.current.reset();
      setOutcome(res);
      setConfirmOpen(false);
      toast.success(res.message || 'Done.');
      const roundId = res.result?.interviewRoundId || res.result?.id || result.round?.id;
      if (res.success && res.actionType === 'BOOK_INTERVIEW' && roundId) {
        navigate(`/interviews/${roundId}`);
      }
    } catch (err) {
      const parsed = parseApiError(err);
      if (parsed.status === 422) {
        setConfirmOpen(false);
        toast.warning('This slot was just taken. Ask again to get the latest available options.', {
          title: 'Slot no longer available',
        });
        idempotency.current.reset();
        ask(result.interpretedRequest || message);
      } else if (parsed.isNetwork) {
        toast.error(
          "Couldn't reach the server. Retrying reuses the same key, so this can't double-book.",
          { title: 'Connection failed' },
        );
      } else {
        toast.apiError(err);
      }
    } finally {
      setConfirming(false);
    }
  };

  const action = result?.action;
  const needsConfirmation = !!action && result.confirmationRequired;

  return (
    <div className="grid gap-5 lg:grid-cols-5">
      <div className="lg:col-span-2">
        <Card>
          <CardHeader
            title="Ask in plain language"
            subtitle="The assistant reads data and proposes — it never books on its own."
          />
          <CardBody className="space-y-4">
            <Textarea
              rows={4}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder="Schedule Rahul's Java technical interview next week for 60 minutes, prefer afternoon and exclude Friday."
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) ask();
              }}
            />
            <Button onClick={() => ask()} loading={thinking} disabled={!message.trim()} className="w-full">
              Ask
            </Button>

            <div>
              <p className="mb-2 text-xs font-medium uppercase tracking-wide text-slate-500">
                Try one of these
              </p>
              <div className="space-y-1.5">
                {EXAMPLES.map((ex) => (
                  <button
                    key={ex}
                    type="button"
                    onClick={() => {
                      setMessage(ex);
                      ask(ex);
                    }}
                    className="block w-full rounded-md border border-slate-200 px-3 py-2 text-left text-xs text-slate-600 hover:border-brand-300 hover:bg-slate-50"
                  >
                    {ex}
                  </button>
                ))}
              </div>
            </div>

            <InfoNote tone="info" title="How this works">
              The model calls read-only tools, then hands back a proposal. Confirming runs it
              through the same booking service the form uses — with the same locking, conflict
              checks and permission rules.
            </InfoNote>
          </CardBody>
        </Card>
      </div>

      <div className="space-y-5 lg:col-span-3">
        {thinking && (
          <Card>
            <CardBody>
              <LoadingState label="Reading the pipeline and searching for slots…" />
            </CardBody>
          </Card>
        )}

        {!thinking && error && (
          <ErrorState error={parseApiError(error)} onRetry={() => ask(message)} />
        )}

        {!thinking && !error && !result && (
          <Card>
            <CardBody>
              <EmptyState
                icon="💬"
                title="Nothing asked yet"
                detail="Describe what you want scheduled. You'll see how it was interpreted before anything happens."
              />
            </CardBody>
          </Card>
        )}

        {!thinking && result && (
          <>
            <Card>
              <CardHeader title="How this was interpreted" />
              <CardBody className="space-y-4">
                <p className="rounded-lg border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-800">
                  {result.interpretedRequest || 'No structured interpretation returned.'}
                </p>

                <div className="flex flex-wrap gap-4">
                  {result.candidate && (
                    <div>
                      <p className="text-xs uppercase tracking-wide text-slate-500">Candidate</p>
                      <p className="mt-0.5 flex items-center gap-2 text-sm font-medium text-slate-900">
                        {result.candidate.name}
                        <CandidateStatusBadge status={result.candidate.status} />
                      </p>
                    </div>
                  )}
                  {result.round && (
                    <div>
                      <p className="text-xs uppercase tracking-wide text-slate-500">Round</p>
                      <p className="mt-0.5 flex items-center gap-2 text-sm font-medium text-slate-900">
                        {humanize(result.round.roundType)}
                        <RoundStatusBadge status={result.round.status} />
                      </p>
                    </div>
                  )}
                </div>

                {result.reason && <p className="text-sm text-slate-700">{result.reason}</p>}
              </CardBody>
            </Card>

            {result.eligibleInterviewers?.length > 0 && (
              <Card>
                <CardHeader title="Eligible interviewers" />
                <CardBody>
                  <InterviewerMatchList
                    match={{ eligibleInterviewers: result.eligibleInterviewers }}
                  />
                </CardBody>
              </Card>
            )}

            {(result.recommendedSlots?.length > 0 ||
              result.alternatives?.length > 0 ||
              (action && action.type === 'BOOK_INTERVIEW')) && (
              <Card>
                <CardHeader
                  title="Recommended slots"
                  subtitle="The same slots, from the same engine, as the form mode."
                />
                <CardBody>
                  <SchedulingResults
                    response={{
                      slots: result.recommendedSlots,
                      alternatives: result.alternatives,
                      reasonCode: result.reasonCode,
                    }}
                    interviewerNames={namesById}
                    selectedSlot={
                      action?.start
                        ? {
                            start: action.start,
                            end: action.end,
                            interviewerId: action.interviewerId,
                          }
                        : null
                    }
                    onRetry={() => ask(result.interpretedRequest || message)}
                  />
                </CardBody>
              </Card>
            )}

            {action && (
              <Card>
                <CardHeader
                  title="Proposed action"
                  action={
                    needsConfirmation ? (
                      <Badge tone="amber">Needs your confirmation</Badge>
                    ) : (
                      <Badge tone="green">Already applied</Badge>
                    )
                  }
                />
                <CardBody className="space-y-4">
                  <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                    <p className="text-sm font-semibold text-slate-900">
                      {AI_ACTION_LABELS[action.type] || action.type}
                    </p>
                    {action.start && (
                      <p className="mt-1 text-sm text-slate-700">
                        {formatTimeRange(action.start, action.end, action.timezone)}
                        {action.timezone && (
                          <span className="ml-1 text-xs text-slate-500">({action.timezone})</span>
                        )}
                      </p>
                    )}
                    {action.reason && (
                      <p className="mt-1 text-xs text-slate-600">{action.reason}</p>
                    )}
                  </div>

                  {needsConfirmation ? (
                    <>
                      <Button onClick={() => setConfirmOpen(true)} className="w-full">
                        Review and confirm
                      </Button>
                      <p className="text-center text-xs text-slate-500">
                        Nothing has been written yet. This is only a proposal.
                      </p>
                    </>
                  ) : (
                    <InfoNote tone="warning" title="This already happened">
                      The server&apos;s replacement policy applied this action immediately, so
                      there&apos;s nothing left to confirm.
                    </InfoNote>
                  )}
                </CardBody>
              </Card>
            )}

            {!action && (
              <InfoNote tone="info" title="Nothing to do">
                This was answered as a question — no action was proposed, and nothing changed.
              </InfoNote>
            )}

            {outcome && !outcome.alreadyApplied && (
              <InfoNote tone={outcome.success ? 'info' : 'warning'} title={outcome.actionType}>
                {outcome.message}
              </InfoNote>
            )}
          </>
        )}
      </div>

      <Modal
        open={confirmOpen}
        title="Confirm this action"
        onClose={() => setConfirmOpen(false)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirmOpen(false)}>
              Cancel
            </Button>
            <Button loading={confirming} onClick={confirm}>
              {AI_ACTION_LABELS[action?.type] || 'Confirm'}
            </Button>
          </>
        }
      >
        {action && (
          <div className="space-y-3 text-sm">
            <p className="font-medium text-slate-900">
              {AI_ACTION_LABELS[action.type] || action.type}
            </p>
            {result?.candidate && (
              <p className="text-slate-700">
                {result.candidate.name}
                {result.round && ` · ${humanize(result.round.roundType)} round`}
              </p>
            )}
            {action.start && (
              <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                <p className="font-medium text-slate-900">
                  {formatTimeRange(action.start, action.end, action.timezone)}
                </p>
                <p className="mt-1 text-xs text-slate-600">
                  {namesById[action.interviewerId] || 'Assigned interviewer'}
                </p>
              </div>
            )}
            <InfoNote tone="info">
              This runs through the same service as a manual booking — fresh availability and
              conflict checks, and a refusal if the slot is gone.
            </InfoNote>
          </div>
        )}
      </Modal>
    </div>
  );
}

/** Standalone route wrapper, for linking straight to the assistant. */
export default function AiSchedulingPage() {
  return (
    <>
      <PageHeader
        title="AI scheduling"
        subtitle="Describe what you need; confirm before anything is written."
      />
      <AiPanel />
    </>
  );
}
