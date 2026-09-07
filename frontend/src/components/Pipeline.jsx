import { Badge, Card, CardBody, CardHeader } from './ui';
import { ProcessStatusBadge, ResultBadge, RoundStatusBadge } from './StatusBadge';
import { formatTimeRange } from '../utils/datetime';
import { humanize } from '../utils/format';
import { ROUND_TYPES } from '../constants/enums';

// A process is always the same fixed 4 rounds in dependency order:
// SCREENING -> TECHNICAL -> MANAGERIAL -> HR.

function stepTone(round) {
  if (!round) return 'bg-slate-100 text-slate-400 ring-slate-200';
  if (round.result === 'PASS') return 'bg-green-100 text-green-800 ring-green-300';
  if (round.result === 'FAIL') return 'bg-red-100 text-red-800 ring-red-300';
  if (round.status === 'CANCELLED') return 'bg-slate-100 text-slate-400 ring-slate-200 line-through';
  if (round.status === 'SCHEDULED') return 'bg-brand-100 text-brand-800 ring-brand-300';
  if (round.status === 'RESCHEDULE_REQUIRED') return 'bg-amber-100 text-amber-900 ring-amber-300';
  if (round.status === 'IN_PROGRESS') return 'bg-indigo-100 text-indigo-800 ring-indigo-300';
  return 'bg-slate-100 text-slate-600 ring-slate-200';
}

/** The four-step visual, driven purely by each round's own status/result. */
export function PipelineTrack({ rounds = [], currentRound }) {
  const byType = {};
  for (const r of rounds) byType[r.roundType] = r;

  return (
    <ol className="flex flex-wrap items-center gap-1">
      {ROUND_TYPES.map((type, i) => {
        const round = byType[type];
        const isCurrent = round && round.roundNumber === currentRound;
        return (
          <li key={type} className="flex items-center gap-1">
            <div
              className={`rounded-lg px-3 py-2 ring-1 ring-inset ${stepTone(round)} ${
                isCurrent ? 'ring-2' : ''
              }`}
            >
              <p className="text-xs font-semibold">{humanize(type)}</p>
              <p className="text-[11px] opacity-80">
                {round ? humanize(round.result === 'PENDING' ? round.status : round.result) : 'Not created'}
              </p>
            </div>
            {i < ROUND_TYPES.length - 1 && <span className="text-slate-300">→</span>}
          </li>
        );
      })}
    </ol>
  );
}

/** One round's summary line — used inside pipeline cards and interview lists. */
export function RoundSummary({ round, interviewerName, actions }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-slate-200 px-4 py-3">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-slate-900">
            Round {round.roundNumber} · {humanize(round.roundType)}
          </span>
          <RoundStatusBadge status={round.status} />
          <ResultBadge result={round.result} hidePending />
        </div>
        <p className="mt-1 text-xs text-slate-600">
          {round.scheduledStart
            ? formatTimeRange(round.scheduledStart, round.scheduledEnd, round.timezone)
            : 'Not scheduled yet'}
          {round.timezone && round.scheduledStart && (
            <span className="ml-1 text-slate-400">({round.timezone})</span>
          )}
        </p>
        <p className="mt-0.5 text-xs text-slate-500">
          {round.durationMinutes} min
          {interviewerName ? ` · ${interviewerName}` : ''}
          {round.rescheduleCount > 0 && (
            <span className="ml-1 text-amber-700">
              · rescheduled {round.rescheduleCount}×
            </span>
          )}
        </p>
      </div>
      {actions && <div className="flex shrink-0 flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

/** One CandidatePipelineEntry: {process, rounds}. */
export function PipelineEntryCard({ entry, renderRoundActions }) {
  const { process, rounds = [] } = entry;
  const ordered = [...rounds].sort((a, b) => a.roundNumber - b.roundNumber);

  return (
    <Card>
      <CardHeader
        title={`Process ${String(process.id).slice(0, 8)}`}
        subtitle={`Job ${String(process.jobId).slice(0, 8)} · round ${process.currentRound} of 4`}
        action={<ProcessStatusBadge status={process.status} />}
      />
      <CardBody className="space-y-4">
        <PipelineTrack rounds={ordered} currentRound={process.currentRound} />
        <div className="space-y-2">
          {ordered.map((round) => (
            <RoundSummary
              key={round.id}
              round={round}
              actions={renderRoundActions?.(round, process)}
            />
          ))}
        </div>
      </CardBody>
    </Card>
  );
}

export function RescheduleCounter({ count }) {
  if (!count) return null;
  return (
    <Badge tone={count >= 2 ? 'amber' : 'slate'}>
      Rescheduled {count}×
    </Badge>
  );
}
