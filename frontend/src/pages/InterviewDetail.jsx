import { useCallback, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { interviewsApi } from '../api/endpoints';
import { useFetch } from '../hooks/useAsync';
import { useInterviewerNames } from '../hooks/useRounds';
import { useToast } from '../context/ToastContext';
import { useAuth } from '../auth/AuthContext';
import { PageHeader } from '../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, CardHeader, DescriptionList, EmptyState,
  ErrorState, Field, InfoNote, Input, LoadingState, Modal, Select,
} from '../components/ui';
import {
  CandidateStatusBadge, ResultBadge, RoundStatusBadge, RoundTypeBadge,
} from '../components/StatusBadge';
import { PipelineTrack } from '../components/Pipeline';
import { SchedulingResults } from '../components/SchedulingResults';
import { browserTimezone, formatDate, formatTimeRange, toBackendTime } from '../utils/datetime';
import { createIdempotencyHolder } from '../utils/idempotency';
import { humanize } from '../utils/format';
import { parseApiError } from '../utils/errors';

/**
 * One round, every action any role can take on it. Which actions appear is driven by
 * role plus the round's own status — the backend enforces both independently.
 */
export default function InterviewDetail() {
  const { roundId } = useParams();
  const toast = useToast();
  const { role, userId } = useAuth();
  const { namesById } = useInterviewerNames();

  // GET /api/interviews/{id} works for staff, the round's own candidate, and any active
  // participant (including an INTERVIEWER) — see InterviewProcessService.getRoundDetail.
  // A 404 or 403 both mean "you can't open this," which the EmptyState below already
  // phrases as one honest, non-committal message rather than distinguishing them.
  const { data, error, loading, reload } = useFetch(async () => {
    try {
      return await interviewsApi.get(roundId);
    } catch (err) {
      const status = err?.response?.status;
      if (status === 404 || status === 403) return null;
      throw err;
    }
  }, [roundId]);

  // Slot lists returned by reschedule / interviewer-cancel / decline — those calls all
  // hand back a SchedulingResponse with fresh candidate slots.
  const [freshSlots, setFreshSlots] = useState(null);
  const [selectedFreshSlot, setSelectedFreshSlot] = useState(null);
  const [booking, setBooking] = useState(false);
  const bookingIdempotency = useRef(createIdempotencyHolder());
  const [replacement, setReplacement] = useState(null);
  const [dialog, setDialog] = useState(null); // 'reschedule' | 'cancel' | ...
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => {
    reload();
    setReplacement(null);
  }, [reload]);

  const bookFreshSlot = async () => {
    if (!selectedFreshSlot) return;
    setBooking(true);
    try {
      await interviewsApi.book(roundId, {
        interviewerId: selectedFreshSlot.interviewerId,
        start: selectedFreshSlot.start,
        end: selectedFreshSlot.end,
        timezone: selectedFreshSlot.timezone,
        idempotencyKey: bookingIdempotency.current.key(),
      });
      bookingIdempotency.current.reset();
      toast.success('Interview booked. Calendar invitations go out from Google directly.');
      setFreshSlots(null);
      setSelectedFreshSlot(null);
      refresh();
    } catch (err) {
      const parsed = parseApiError(err);
      if (parsed.status === 422) {
        setSelectedFreshSlot(null);
        toast.warning('This slot was just taken. Pick another one below.', {
          title: 'Slot no longer available',
        });
        bookingIdempotency.current.reset();
      } else {
        toast.apiError(err);
      }
    } finally {
      setBooking(false);
    }
  };

  if (loading) return <LoadingState label="Loading interview…" />;
  if (error) return <ErrorState error={parseApiError(error)} onRetry={reload} />;
  if (!data) {
    return (
      <EmptyState
        icon="🔎"
        title="Interview not found"
        detail="It may have been removed, or it belongs to a candidate you can't see."
      />
    );
  }

  const { round, process, rounds, candidate } = data;
  const isStaff = role === 'RECRUITER' || role === 'ADMIN';
  // Reliable, not just assumed: GET /api/interviews/{id} itself only succeeds for an
  // INTERVIEWER who is an active participant on this exact round (see
  // InterviewProcessService.getRoundDetail) — a non-participant interviewer never reaches
  // this render at all, so no separate ownership check is needed here.
  const isAssignedInterviewer = role === 'INTERVIEWER';
  const isOwnCandidate = role === 'CANDIDATE' && candidate?.userId === userId;
  const isTerminalRound = ['COMPLETED', 'CANCELLED'].includes(round.status);
  const isScheduled = round.status === 'SCHEDULED';
  const needsReschedule = round.status === 'RESCHEDULE_REQUIRED';

  const act = async (fn, successMessage, { slotsFromResponse = false } = {}) => {
    setBusy(true);
    try {
      const res = await fn();
      toast.success(successMessage);
      setDialog(null);
      if (slotsFromResponse) setFreshSlots(res);
      refresh();
      return res;
    } catch (err) {
      const parsed = parseApiError(err);
      if (parsed.status === 409) {
        // The most common 409 here is the configured reschedule maximum.
        toast.error(parsed.message, { title: 'Not allowed in this state' });
      } else {
        toast.apiError(err);
      }
      throw err;
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader
        breadcrumb={
          <Link
            to={isStaff ? `/candidates/${candidate.id}` : '/'}
            className="hover:underline"
          >
            ← {isStaff ? candidate.name : 'Back'}
          </Link>
        }
        title={`${humanize(round.roundType)} interview`}
        subtitle={`${candidate.name} · round ${round.roundNumber} of 4`}
        actions={
          <div className="flex flex-wrap gap-2">
            <RoundStatusBadge status={round.status} />
            <ResultBadge result={round.result} hidePending />
          </div>
        }
      />

      {needsReschedule && (
        <InfoNote tone="warning" className="mb-5" title="Interview requires rescheduling">
          This round lost its slot. The candidate&apos;s pipeline stage is unchanged — only a
          pass/fail result moves that. Pick a new slot below, or find a replacement interviewer.
        </InfoNote>
      )}

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          <Card>
            <CardHeader title="Details" action={<RoundTypeBadge type={round.roundType} />} />
            <CardBody>
              <DescriptionList
                items={[
                  {
                    label: 'Scheduled',
                    value: round.scheduledStart
                      ? formatTimeRange(round.scheduledStart, round.scheduledEnd)
                      : 'Not scheduled',
                  },
                  {
                    label: 'Shown in your time zone',
                    value: browserTimezone(),
                  },
                  { label: 'Duration', value: `${round.durationMinutes} minutes` },
                  { label: 'Buffer', value: `${round.bufferMinutes ?? 0} minutes` },
                  {
                    label: 'Interviewer',
                    value: (
                      <span className="text-slate-500">
                        Not returned by the API
                      </span>
                    ),
                  },
                  {
                    label: 'Reschedules',
                    value: (
                      <span className="flex items-center gap-2">
                        {round.rescheduleCount ?? 0}
                        {round.rescheduleCount > 0 && <Badge tone="amber">rescheduled</Badge>}
                      </span>
                    ),
                  },
                  { label: 'Round status', value: <RoundStatusBadge status={round.status} /> },
                  { label: 'Result', value: <ResultBadge result={round.result} /> },
                ]}
              />

              {/* Two documented gaps, stated rather than papered over with a guess. */}
              <InfoNote tone="gap" className="mt-4" title="Who's interviewing?">
                The round response doesn&apos;t include the assigned interviewer, so it
                can&apos;t be shown here. It&apos;s on the calendar invitation, and on the slot
                that was booked.
              </InfoNote>

              {isScheduled && (
                <InfoNote tone="gap" className="mt-3" title="Joining the call">
                  The Google Meet link is delivered on Google&apos;s own calendar invitation to each
                  participant&apos;s email. The backend doesn&apos;t return it as a field, so it
                  can&apos;t be shown here.
                </InfoNote>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Actions"
              subtitle="What you can do depends on your role and this round's state."
            />
            <CardBody className="flex flex-wrap gap-2">
              {isTerminalRound && (
                <p className="text-sm text-slate-500">
                  This round is {humanize(round.status).toLowerCase()} — no further actions are
                  available.
                </p>
              )}

              {/* --- staff --- */}
              {isStaff && !isTerminalRound && (
                <>
                  {(round.status === 'PENDING' || needsReschedule) && (
                    <Link
                      to={`/recruiter/scheduling?candidateId=${candidate.id}`}
                    >
                      <Button>Find a slot</Button>
                    </Link>
                  )}
                  {isScheduled && (
                    <Button
                      variant="secondary"
                      onClick={() =>
                        act(() => interviewsApi.complete(roundId), 'Round marked complete.')
                      }
                      disabled={busy}
                    >
                      Mark complete
                    </Button>
                  )}
                  {['SCHEDULED', 'IN_PROGRESS', 'COMPLETED'].includes(round.status) &&
                    round.result === 'PENDING' && (
                      <Button variant="secondary" onClick={() => setDialog('result')}>
                        Submit result
                      </Button>
                    )}
                  {(isScheduled || needsReschedule) && (
                    <Button variant="secondary" onClick={() => setDialog('reschedule')}>
                      Reschedule
                    </Button>
                  )}
                  {(isScheduled || needsReschedule) && (
                    <Button
                      variant="secondary"
                      loading={busy}
                      onClick={async () => {
                        setBusy(true);
                        try {
                          setReplacement(await interviewsApi.findReplacement(roundId));
                        } catch (err) {
                          toast.apiError(err);
                        } finally {
                          setBusy(false);
                        }
                      }}
                    >
                      Find replacement interviewer
                    </Button>
                  )}
                  <Button variant="danger" onClick={() => setDialog('cancel')}>
                    Cancel interview
                  </Button>
                </>
              )}

              {/* --- assigned interviewer --- */}
              {isAssignedInterviewer && !isTerminalRound && (
                <>
                  {isScheduled && (
                    <Button
                      variant="secondary"
                      disabled={busy}
                      onClick={() =>
                        act(() => interviewsApi.complete(roundId), 'Round marked complete.')
                      }
                    >
                      Mark complete
                    </Button>
                  )}
                  {['SCHEDULED', 'IN_PROGRESS', 'COMPLETED'].includes(round.status) &&
                    round.result === 'PENDING' && (
                      <Button variant="secondary" onClick={() => setDialog('result')}>
                        Submit result
                      </Button>
                    )}
                  <Button
                    variant="danger"
                    disabled={busy}
                    onClick={() =>
                      act(
                        () => interviewsApi.interviewerCancel(roundId),
                        'Cancelled. A replacement search ran automatically.',
                        { slotsFromResponse: true },
                      )
                    }
                  >
                    Cancel interview
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() =>
                      act(
                        () => interviewsApi.decline(roundId),
                        'Invitation declined. The recruiter was notified.',
                        { slotsFromResponse: true },
                      )
                    }
                  >
                    Decline invitation
                  </Button>
                  <Button variant="secondary" onClick={() => setDialog('reschedule')}>
                    Request a new time
                  </Button>
                </>
              )}

              {/* --- the round's own candidate --- */}
              {isOwnCandidate && !isTerminalRound && (
                <>
                  <Button variant="secondary" onClick={() => setDialog('reschedule')}>
                    Request a reschedule
                  </Button>
                  <Button
                    variant="danger"
                    disabled={busy || round.status === 'IN_PROGRESS'}
                    onClick={() =>
                      act(
                        () => interviewsApi.candidateCancel(roundId),
                        'Interview cancelled. Your pipeline stage is unchanged.',
                      )
                    }
                  >
                    Cancel my interview
                  </Button>
                </>
              )}
            </CardBody>
          </Card>

          {freshSlots && (
            <Card>
              <CardHeader
                title="New slots for this round"
                subtitle="Returned by the same call that released the old slot."
                action={
                  <button
                    type="button"
                    onClick={() => {
                      setFreshSlots(null);
                      setSelectedFreshSlot(null);
                    }}
                    className="text-xs text-slate-500 hover:underline"
                  >
                    Dismiss
                  </button>
                }
              />
              <CardBody>
                {isStaff || isOwnCandidate ? (
                  <SchedulingResults
                    response={freshSlots}
                    interviewerNames={namesById}
                    selectedSlot={selectedFreshSlot}
                    onSelectSlot={setSelectedFreshSlot}
                    disabled={booking}
                    footer={
                      selectedFreshSlot && (
                        <Button className="w-full" loading={booking} onClick={bookFreshSlot}>
                          Confirm booking
                        </Button>
                      )
                    }
                  />
                ) : (
                  <SchedulingResults response={freshSlots} interviewerNames={namesById} />
                )}
              </CardBody>
            </Card>
          )}

          {replacement && (
            <ReplacementPanel
              replacement={replacement}
              roundId={roundId}
              namesById={namesById}
              onClose={() => setReplacement(null)}
              onSwitched={() => {
                setReplacement(null);
                refresh();
              }}
            />
          )}
        </div>

        <div className="space-y-5">
          <Card>
            <CardHeader
              title="Candidate"
              action={<CandidateStatusBadge status={candidate.currentStatus} />}
            />
            <CardBody className="space-y-3">
              <div>
                <p className="text-sm font-medium text-slate-900">{candidate.name}</p>
                <p className="text-xs text-slate-500">{candidate.email}</p>
              </div>
              {isStaff && (
                <Link to={`/candidates/${candidate.id}`}>
                  <Button variant="secondary" size="sm">
                    Open candidate
                  </Button>
                </Link>
              )}
              {/* Two different fields from two different endpoints — never merged. */}
              <InfoNote tone="gap">
                The candidate&apos;s pipeline status and this round&apos;s status are separate.
                Cancelling or rescheduling a round never moves the pipeline; only a pass or fail
                result does.
              </InfoNote>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Process" subtitle={`Created ${formatDate(process.createdAt)}`} />
            <CardBody className="space-y-3">
              <PipelineTrack rounds={rounds} currentRound={process.currentRound} />
              <div className="space-y-1">
                {[...rounds]
                  .sort((a, b) => a.roundNumber - b.roundNumber)
                  .map((r) => (
                    <Link
                      key={r.id}
                      to={`/interviews/${r.id}`}
                      className={`flex items-center justify-between rounded-md px-3 py-2 text-xs ${
                        r.id === roundId ? 'bg-brand-50 font-medium' : 'hover:bg-slate-50'
                      }`}
                    >
                      <span>
                        {r.roundNumber}. {humanize(r.roundType)}
                      </span>
                      <RoundStatusBadge status={r.status} />
                    </Link>
                  ))}
              </div>
            </CardBody>
          </Card>
        </div>
      </div>

      <RescheduleModal
        open={dialog === 'reschedule'}
        onClose={() => setDialog(null)}
        busy={busy}
        onSubmit={(body) =>
          act(
            () => interviewsApi.reschedule(roundId, body),
            'Rescheduling started — here are fresh slots.',
            { slotsFromResponse: true },
          ).catch(() => {})
        }
      />

      <ResultModal
        open={dialog === 'result'}
        onClose={() => setDialog(null)}
        busy={busy}
        roundType={round.roundType}
        onSubmit={(value) =>
          act(
            () => interviewsApi.result(roundId, value),
            value === 'PASS'
              ? 'Pass recorded. The candidate advances.'
              : value === 'FAIL'
                ? 'Fail recorded. The candidate is rejected and later rounds were cancelled.'
                : 'Result put on hold. The pipeline is unchanged.',
          ).catch(() => {})
        }
      />

      <Modal
        open={dialog === 'cancel'}
        title="Cancel this interview?"
        onClose={() => setDialog(null)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setDialog(null)}>
              Keep it
            </Button>
            <Button
              variant="danger"
              loading={busy}
              onClick={() =>
                act(() => interviewsApi.cancel(roundId), 'Interview cancelled.').catch(() => {})
              }
            >
              Cancel interview
            </Button>
          </>
        }
      >
        <p className="text-sm text-slate-700">
          This is a hard cancel — the round is cancelled outright with no replacement search. If
          you want a new time instead, use <strong>Reschedule</strong>.
        </p>
      </Modal>
    </>
  );
}

/* ------------------------------------------------------- replacement ---- */

function ReplacementPanel({ replacement, roundId, namesById, onClose, onSwitched }) {
  const toast = useToast();
  const [selected, setSelected] = useState(null);
  const [switching, setSwitching] = useState(false);
  const idempotency = useRef(createIdempotencyHolder());
  const options = replacement.options || [];

  const commit = async () => {
    if (!selected) return;
    setSwitching(true);
    try {
      await interviewsApi.switchInterviewer(roundId, {
        newInterviewerId: selected.interviewerId,
        start: selected.start,
        end: selected.end,
        timezone: selected.timezone || replacement.timezone,
        idempotencyKey: idempotency.current.key(),
      });
      idempotency.current.reset();
      toast.success('Interviewer switched and the slot confirmed.');
      onSwitched();
    } catch (err) {
      const parsed = parseApiError(err);
      if (parsed.status === 422) {
        toast.warning('That slot was just taken. Search for replacements again.', {
          title: 'Slot no longer available',
        });
        idempotency.current.reset();
        onClose();
      } else {
        toast.apiError(err);
      }
    } finally {
      setSwitching(false);
    }
  };

  return (
    <Card>
      <CardHeader
        title="Replacement interviewers"
        subtitle={`Current: ${replacement.currentInterviewerName || 'unassigned'} · policy ${humanize(replacement.policy)}`}
        action={
          <button type="button" onClick={onClose} className="text-xs text-slate-500 hover:underline">
            Dismiss
          </button>
        }
      />
      <CardBody className="space-y-3">
        {options.length === 0 ? (
          <EmptyState
            icon="🙁"
            title="No replacement available"
            detail="Nobody else qualifies for this round, or no qualified interviewer has a free slot. Try rescheduling to a wider window instead."
          />
        ) : (
          <>
            {options.map((opt) => {
              const isSel =
                selected?.interviewerId === opt.interviewerId && selected?.start === opt.start;
              return (
                <button
                  key={`${opt.interviewerId}-${opt.start}`}
                  type="button"
                  onClick={() => setSelected(opt)}
                  className={`flex w-full items-center justify-between rounded-lg border px-4 py-3 text-left ${
                    isSel
                      ? 'border-brand-500 bg-brand-50 ring-1 ring-brand-500'
                      : 'border-slate-200 hover:bg-slate-50'
                  }`}
                >
                  <div>
                    <p className="text-sm font-medium text-slate-900">
                      {opt.interviewerName || namesById[opt.interviewerId] || 'Interviewer'}
                    </p>
                    <p className="text-xs text-slate-600">
                      {opt.start
                        ? formatTimeRange(opt.start, opt.end, opt.timezone)
                        : 'Keeps the current time'}
                    </p>
                    {opt.reason && <p className="mt-0.5 text-xs text-slate-500">{opt.reason}</p>}
                  </div>
                  <span className="flex shrink-0 items-center gap-2">
                    {opt.priority && <Badge tone="slate">{humanize(opt.priority)}</Badge>}
                    {opt.score != null && (
                      <Badge tone="blue">score {Number(opt.score).toFixed(1)}</Badge>
                    )}
                  </span>
                </button>
              );
            })}
            <Button className="w-full" disabled={!selected} loading={switching} onClick={commit}>
              Confirm replacement
            </Button>
          </>
        )}
      </CardBody>
    </Card>
  );
}

/* ------------------------------------------------------------ dialogs ---- */

function RescheduleModal({ open, onClose, onSubmit, busy }) {
  const [form, setForm] = useState({
    reason: '', dateFrom: '', dateTo: '', preferredTimeStart: '', preferredTimeEnd: '',
  });
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = (e) => {
    e.preventDefault();
    // Every field is optional; send only what was filled in.
    const body = {};
    if (form.reason.trim()) body.reason = form.reason.trim();
    if (form.dateFrom) body.dateFrom = form.dateFrom;
    if (form.dateTo) body.dateTo = form.dateTo;
    if (form.preferredTimeStart) body.preferredTimeStart = toBackendTime(form.preferredTimeStart);
    if (form.preferredTimeEnd) body.preferredTimeEnd = toBackendTime(form.preferredTimeEnd);
    onSubmit(body);
  };

  return (
    <Modal
      open={open}
      title="Reschedule this interview"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button form="reschedule-form" type="submit" loading={busy}>
            Release slot and find new times
          </Button>
        </>
      }
    >
      <form id="reschedule-form" onSubmit={submit} className="space-y-4">
        <InfoNote tone="info">
          This releases the current slot, moves the round to <strong>Reschedule required</strong>,
          and immediately returns fresh candidate times. The candidate&apos;s pipeline stage
          doesn&apos;t change. Every field below is optional.
        </InfoNote>
        <Field label="Reason">
          <Input value={form.reason} onChange={set('reason')} placeholder="Conflict came up" />
        </Field>
        <div className="grid grid-cols-2 gap-4">
          <Field label="Search from">
            <Input type="date" value={form.dateFrom} onChange={set('dateFrom')} />
          </Field>
          <Field label="Search to">
            <Input type="date" value={form.dateTo} onChange={set('dateTo')} />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-4">
          <Field label="Preferred from">
            <Input
              type="time"
              value={form.preferredTimeStart}
              onChange={set('preferredTimeStart')}
            />
          </Field>
          <Field label="Preferred to">
            <Input type="time" value={form.preferredTimeEnd} onChange={set('preferredTimeEnd')} />
          </Field>
        </div>
      </form>
    </Modal>
  );
}

function ResultModal({ open, onClose, onSubmit, busy, roundType }) {
  const [value, setValue] = useState('PASS');

  return (
    <Modal
      open={open}
      title="Submit the result"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button
            loading={busy}
            variant={value === 'FAIL' ? 'danger' : 'primary'}
            onClick={() => onSubmit(value)}
          >
            Submit {humanize(value)}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label={`Result for the ${humanize(roundType).toLowerCase()} round`} required>
          <Select value={value} onChange={(e) => setValue(e.target.value)}>
            <option value="PASS">Pass — advance to the next round</option>
            <option value="FAIL">Fail — reject the candidate</option>
            <option value="HOLD">Hold — no change to the pipeline</option>
          </Select>
        </Field>

        {value === 'FAIL' && (
          <InfoNote tone="warning" title="This cascades">
            A fail rejects the candidate and cancels every later round in this process. There is no
            undo endpoint.
          </InfoNote>
        )}
        {value === 'PASS' && (
          <InfoNote tone="info">
            A pass advances the candidate to the next round — or marks them selected, if this is the
            last one.
          </InfoNote>
        )}
      </div>
    </Modal>
  );
}
