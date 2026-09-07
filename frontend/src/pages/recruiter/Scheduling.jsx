import { useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  candidatesApi, interviewersApi, interviewsApi, schedulingApi,
} from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { useInterviewerNames } from '../../hooks/useRounds';
import { useToast } from '../../context/ToastContext';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Button, Card, CardBody, CardHeader, EmptyState, ErrorState, Field,
  InfoNote, Input, LoadingState, Modal, Select,
} from '../../components/ui';
import { CandidateStatusBadge, RoundStatusBadge } from '../../components/StatusBadge';
import { InterviewerMatchList, SchedulingResults } from '../../components/SchedulingResults';
import { AiPanel } from './AiScheduling';
import { CANDIDATE_TERMINAL_STATUSES, WEEKDAYS } from '../../constants/enums';
import {
  browserTimezone, daysFromToday, formatTimeRange, timezoneOptions,
  toBackendTime, todayInput,
} from '../../utils/datetime';
import { createIdempotencyHolder } from '../../utils/idempotency';
import { humanize } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

// Two ways in — a structured form and natural language — that deliberately land on the
// same slot component and the same booking call. The AI is a second front door to the
// deterministic engine, not a shortcut around it.

export default function Scheduling() {
  const [params, setParams] = useSearchParams();
  const [mode, setMode] = useState(params.get('mode') === 'ai' ? 'ai' : 'form');

  const switchMode = (next) => {
    setMode(next);
    const p = new URLSearchParams(params);
    p.set('mode', next);
    setParams(p, { replace: true });
  };

  return (
    <>
      <PageHeader
        title="Schedule an interview"
        subtitle="Both modes call the same scheduling engine and the same booking endpoint."
        actions={
          <div className="inline-flex rounded-md border border-slate-300 bg-white p-0.5">
            {[
              { key: 'form', label: 'Form' },
              { key: 'ai', label: 'Ask in words' },
            ].map((m) => (
              <button
                key={m.key}
                type="button"
                onClick={() => switchMode(m.key)}
                className={`rounded px-4 py-1.5 text-sm font-medium transition-colors ${
                  mode === m.key ? 'bg-brand-600 text-white' : 'text-slate-600 hover:bg-slate-100'
                }`}
              >
                {m.label}
              </button>
            ))}
          </div>
        }
      />
      {mode === 'form' ? <FormScheduling /> : <AiPanel />}
    </>
  );
}

/* ------------------------------------------------------------ form mode ---- */

function FormScheduling() {
  const toast = useToast();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { timezone: userTz } = useAuth();
  const candidates = useFetch(() => candidatesApi.list(), []);
  const { interviewers, namesById } = useInterviewerNames();

  const [form, setForm] = useState({
    candidateId: params.get('candidateId') || '',
    roundId: '',
    preferredInterviewerId: '',
    durationMinutes: 60,
    dateFrom: todayInput(),
    dateTo: daysFromToday(7),
    preferredTimeStart: '',
    preferredTimeEnd: '',
    excludedDays: [],
    timezone: userTz || browserTimezone(),
  });

  const [match, setMatch] = useState(null);
  const [matchLoading, setMatchLoading] = useState(false);
  const [results, setResults] = useState(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState(null);
  const [selectedSlot, setSelectedSlot] = useState(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [booking, setBooking] = useState(false);

  // One key per booking *action*, reused across retries of that same action so a lost
  // response can't turn into a double booking.
  const idempotency = useRef(createIdempotencyHolder());

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const selectedCandidate = (candidates.data || []).find((c) => c.id === form.candidateId);

  // A round is what actually gets booked, and only rounds from the candidate's active
  // process are bookable. Refetched whenever the selected candidate changes.
  const pipeline = useFetch(() => candidatesApi.pipeline(form.candidateId), [form.candidateId], {
    skip: !form.candidateId,
  });
  const pipelineLoading = pipeline.loading;

  const bookableRounds = useMemo(() => {
    const active = (pipeline.data || []).find((e) => e.process.status === 'ACTIVE');
    if (!active) return [];
    return (active.rounds || [])
      .filter((r) => ['PENDING', 'SCHEDULING', 'RESCHEDULE_REQUIRED'].includes(r.status))
      .sort((a, b) => a.roundNumber - b.roundNumber);
  }, [pipeline.data]);

  const selectedRound = bookableRounds.find((r) => r.id === form.roundId);
  const isTerminalCandidate =
    selectedCandidate && CANDIDATE_TERMINAL_STATUSES.includes(selectedCandidate.currentStatus);

  const buildRequest = () => ({
    candidateId: form.candidateId,
    roundId: form.roundId,
    preferredInterviewerId: form.preferredInterviewerId || undefined,
    durationMinutes: Number(form.durationMinutes),
    dateFrom: form.dateFrom,
    dateTo: form.dateTo,
    preferredTimeStart: toBackendTime(form.preferredTimeStart),
    preferredTimeEnd: toBackendTime(form.preferredTimeEnd),
    excludedDays: form.excludedDays.length ? form.excludedDays : undefined,
    timezone: form.timezone,
  });

  const runSearch = async () => {
    setSearching(true);
    setSearchError(null);
    setSelectedSlot(null);
    try {
      const res = await schedulingApi.recommend(buildRequest());
      setResults(res);
      // An empty slots array with a reasonCode is a normal 200, not an error — it
      // renders as its own state rather than a caught exception.
      if (!res.slots?.length && !res.alternatives?.length) {
        toast.info('No bookable slots for that request — see the explanation below.');
      }
    } catch (err) {
      setSearchError(err);
      toast.apiError(err);
    } finally {
      setSearching(false);
    }
  };

  const onSubmit = (e) => {
    e.preventDefault();
    idempotency.current.reset(); // a new search starts a new booking action
    runSearch();
  };

  const loadMatch = async () => {
    if (!form.roundId) return;
    setMatchLoading(true);
    try {
      setMatch(await interviewersApi.match(form.roundId));
    } catch (err) {
      toast.apiError(err);
    } finally {
      setMatchLoading(false);
    }
  };

  const book = async () => {
    if (!selectedSlot) return;
    setBooking(true);
    try {
      const res = await interviewsApi.book(form.roundId, {
        interviewerId: selectedSlot.interviewerId,
        start: selectedSlot.start,
        end: selectedSlot.end,
        timezone: form.timezone,
        idempotencyKey: idempotency.current.key(),
      });
      idempotency.current.reset();
      toast.success('Interview booked. Calendar invitations go out from Google directly.');
      setConfirmOpen(false);
      navigate(`/interviews/${res.interviewRoundId || form.roundId}`);
    } catch (err) {
      const parsed = parseApiError(err);
      if (parsed.status === 422) {
        // The backend re-validates at booking time; a slot from moments ago can be gone.
        setConfirmOpen(false);
        setSelectedSlot(null);
        toast.warning('This slot was just taken. Here are the latest available options.', {
          title: 'Slot no longer available',
        });
        idempotency.current.reset();
        runSearch();
      } else if (parsed.isNetwork) {
        // Keep the same key: the request may have succeeded with the response lost.
        toast.error(
          "Couldn't reach the server. If you retry, we'll reuse the same booking key so this can't double-book.",
          { title: 'Connection failed' },
        );
      } else {
        toast.apiError(err);
      }
    } finally {
      setBooking(false);
    }
  };

  const toggleDay = (day) => {
    setForm((f) => ({
      ...f,
      excludedDays: f.excludedDays.includes(day)
        ? f.excludedDays.filter((d) => d !== day)
        : [...f.excludedDays, day],
    }));
  };

  if (candidates.loading) return <LoadingState label="Loading candidates…" />;
  if (candidates.error) {
    return <ErrorState error={parseApiError(candidates.error)} onRetry={candidates.reload} />;
  }

  return (
    <div className="grid gap-5 lg:grid-cols-5">
      <div className="lg:col-span-2">
        <Card>
          <CardHeader title="Scheduling request" subtitle="Everything the engine needs to search." />
          <CardBody>
            <form onSubmit={onSubmit} className="space-y-4">
              <Field label="Candidate" required>
                <Select
                  value={form.candidateId}
                  onChange={(e) => {
                    // Reset the dependent selections here rather than in an effect —
                    // it's the same user action, not a reaction to one.
                    setForm((f) => ({ ...f, candidateId: e.target.value, roundId: '' }));
                    setMatch(null);
                    setResults(null);
                    setSelectedSlot(null);
                  }}
                  required
                >
                  <option value="">Select a candidate…</option>
                  {(candidates.data || []).map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} · {humanize(c.currentStatus)}
                    </option>
                  ))}
                </Select>
              </Field>

              {isTerminalCandidate && (
                <InfoNote tone="warning" title="This candidate isn't in an active pipeline">
                  They are {humanize(selectedCandidate.currentStatus).toLowerCase()}, so no round
                  can be scheduled for them.
                </InfoNote>
              )}

              <Field
                label="Round"
                required
                hint={
                  form.candidateId && !pipelineLoading && bookableRounds.length === 0
                    ? undefined
                    : 'Only rounds waiting to be scheduled are listed.'
                }
                error={
                  form.candidateId && !pipelineLoading && bookableRounds.length === 0
                    ? 'No round is waiting to be scheduled for this candidate.'
                    : undefined
                }
              >
                <Select
                  value={form.roundId}
                  onChange={(e) => {
                    set('roundId')(e);
                    setMatch(null);
                  }}
                  required
                  disabled={!form.candidateId || pipelineLoading}
                >
                  <option value="">
                    {pipelineLoading ? 'Loading rounds…' : 'Select a round…'}
                  </option>
                  {bookableRounds.map((r) => (
                    <option key={r.id} value={r.id}>
                      Round {r.roundNumber} · {humanize(r.roundType)} · {humanize(r.status)}
                    </option>
                  ))}
                </Select>
              </Field>

              <div className="grid grid-cols-2 gap-4">
                <Field label="From" required>
                  <Input type="date" required value={form.dateFrom} onChange={set('dateFrom')} />
                </Field>
                <Field label="To" required>
                  <Input type="date" required value={form.dateTo} onChange={set('dateTo')} />
                </Field>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <Field label="Duration (minutes)" required>
                  <Input
                    type="number"
                    min={15}
                    step={15}
                    required
                    value={form.durationMinutes}
                    onChange={set('durationMinutes')}
                  />
                </Field>
                <Field label="Timezone" required>
                  <Select value={form.timezone} onChange={set('timezone')} required>
                    {timezoneOptions().map((tz) => (
                      <option key={tz} value={tz}>
                        {tz}
                      </option>
                    ))}
                  </Select>
                </Field>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <Field label="Preferred time from" hint="Optional">
                  <Input
                    type="time"
                    value={form.preferredTimeStart}
                    onChange={set('preferredTimeStart')}
                  />
                </Field>
                <Field label="Preferred time to" hint="Optional">
                  <Input
                    type="time"
                    value={form.preferredTimeEnd}
                    onChange={set('preferredTimeEnd')}
                  />
                </Field>
              </div>

              <Field label="Preferred interviewer" hint="Optional — a preference, not a guarantee.">
                <Select value={form.preferredInterviewerId} onChange={set('preferredInterviewerId')}>
                  <option value="">No preference</option>
                  {interviewers.map((i) => (
                    <option key={i.id} value={i.id}>
                      {i.name} · {i.designation || i.department}
                    </option>
                  ))}
                </Select>
              </Field>

              <div>
                <span className="mb-1 block text-xs font-medium text-slate-700">Exclude days</span>
                <div className="flex flex-wrap gap-1.5">
                  {WEEKDAYS.map((d) => {
                    const on = form.excludedDays.includes(d);
                    return (
                      <button
                        key={d}
                        type="button"
                        onClick={() => toggleDay(d)}
                        className={`rounded-md border px-2.5 py-1 text-xs font-medium transition-colors ${
                          on
                            ? 'border-red-300 bg-red-50 text-red-800'
                            : 'border-slate-300 bg-white text-slate-600 hover:bg-slate-50'
                        }`}
                      >
                        {d.slice(0, 3)}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="flex gap-2 pt-1">
                <Button
                  type="submit"
                  loading={searching}
                  disabled={!form.candidateId || !form.roundId || isTerminalCandidate}
                  className="flex-1"
                >
                  Find slots
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={loadMatch}
                  loading={matchLoading}
                  disabled={!form.roundId}
                >
                  Who qualifies?
                </Button>
              </div>
            </form>
          </CardBody>
        </Card>
      </div>

      <div className="space-y-5 lg:col-span-3">
        {selectedCandidate && selectedRound && (
          <Card>
            <CardBody className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <p className="text-sm font-medium text-slate-900">{selectedCandidate.name}</p>
                <p className="text-xs text-slate-600">
                  Round {selectedRound.roundNumber} · {humanize(selectedRound.roundType)}
                  {selectedRound.rescheduleCount > 0 &&
                    ` · rescheduled ${selectedRound.rescheduleCount}×`}
                </p>
              </div>
              <div className="flex gap-2">
                <CandidateStatusBadge status={selectedCandidate.currentStatus} />
                <RoundStatusBadge status={selectedRound.status} />
              </div>
            </CardBody>
          </Card>
        )}

        {match && (
          <Card>
            <CardHeader
              title="Interviewer matching"
              subtitle="Required skills are disqualifying; domain, primary skill and workload only rank."
              action={
                <button
                  type="button"
                  onClick={() => setMatch(null)}
                  className="text-xs text-slate-500 hover:underline"
                >
                  Hide
                </button>
              }
            />
            <CardBody>
              <InterviewerMatchList match={match} />
            </CardBody>
          </Card>
        )}

        <Card>
          <CardHeader
            title="Recommended slots"
            subtitle={results ? 'Pick one, then confirm to book it.' : undefined}
          />
          <CardBody>
            {searching && <LoadingState label="Searching for slots…" />}
            {!searching && searchError && (
              <ErrorState error={parseApiError(searchError)} onRetry={runSearch} />
            )}
            {!searching && !searchError && !results && (
              <EmptyState
                icon="🔍"
                title="No search yet"
                detail="Fill in the request and select Find slots. Nothing is booked until you confirm."
              />
            )}
            {!searching && !searchError && results && (
              <SchedulingResults
                response={results}
                interviewerNames={namesById}
                selectedSlot={selectedSlot}
                onSelectSlot={setSelectedSlot}
                onRetry={runSearch}
                footer={
                  selectedSlot && (
                    <Button className="w-full" onClick={() => setConfirmOpen(true)}>
                      Confirm and book this slot
                    </Button>
                  )
                }
              />
            )}
          </CardBody>
        </Card>
      </div>

      <Modal
        open={confirmOpen}
        title="Confirm this booking"
        onClose={() => setConfirmOpen(false)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirmOpen(false)}>
              Cancel
            </Button>
            <Button loading={booking} onClick={book}>
              Book interview
            </Button>
          </>
        }
      >
        {selectedSlot && (
          <div className="space-y-3 text-sm">
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
              <p className="font-medium text-slate-900">
                {formatTimeRange(selectedSlot.start, selectedSlot.end, form.timezone)}
              </p>
              <p className="mt-1 text-xs text-slate-600">
                {namesById[selectedSlot.interviewerId] || 'Assigned interviewer'} ·{' '}
                {form.timezone}
              </p>
            </div>
            <p className="text-slate-700">
              {selectedCandidate?.name} · round {selectedRound?.roundNumber},{' '}
              {humanize(selectedRound?.roundType)}
            </p>
            <InfoNote tone="info">
              The backend re-checks availability and conflicts before it commits. If someone took
              this slot in the meantime, the booking is refused and we&apos;ll fetch fresh options.
            </InfoNote>
          </div>
        )}
      </Modal>
    </div>
  );
}
