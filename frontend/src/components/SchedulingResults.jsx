import { useState } from 'react';
import { Badge, Button, EmptyState, InfoNote } from './ui';
import { formatTimeRange } from '../utils/datetime';
import { CONFLICT_TYPE_LABELS, REASON_CODE_MESSAGES } from '../constants/enums';

// Deliberately shared by BOTH the form flow and the AI flow. The AI is a second way
// to reach the same deterministic engine, not a shortcut around it — showing the same
// slot component in both places is what makes that visible rather than merely true.

/** A single recommended slot. Nothing here is booked until the backend says so. */
function SlotRow({ slot, interviewerName, selected, onSelect, disabled, rank }) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={() => onSelect?.(slot)}
      className={`flex w-full items-start justify-between gap-4 rounded-lg border px-4 py-3 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${
        selected
          ? 'border-brand-500 bg-brand-50 ring-1 ring-brand-500'
          : 'border-slate-200 bg-white hover:border-brand-300 hover:bg-slate-50'
      }`}
    >
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-slate-900">
            {formatTimeRange(slot.start, slot.end, slot.timezone)}
          </span>
          {rank === 0 && <Badge tone="green">Top pick</Badge>}
        </div>
        <p className="mt-0.5 text-xs text-slate-600">
          {slot.interviewerName ||
            interviewerName ||
            `Interviewer ${String(slot.interviewerId || '').slice(0, 8)}`}
        </p>
        {slot.reason && <p className="mt-1 text-xs text-slate-500">{slot.reason}</p>}
      </div>
      <div className="shrink-0 text-right">
        {slot.score != null && (
          <span className="text-xs font-medium text-slate-500">
            score {Number(slot.score).toFixed(1)}
          </span>
        )}
        {selected && <p className="mt-1 text-xs font-semibold text-brand-700">Selected</p>}
      </div>
    </button>
  );
}

/**
 * Empty slots are a normal 200 with a reasonCode, not an error — this state is
 * visually distinct from both a loading state and an error state on purpose.
 */
export function NoSlotsState({ reasonCode, onRetry }) {
  const info = REASON_CODE_MESSAGES[reasonCode];
  return (
    <div className="rounded-lg border border-amber-200 bg-amber-50 p-5">
      <p className="text-sm font-semibold text-amber-900">
        {info?.title || 'No slots available'}
      </p>
      <p className="mt-1 text-xs text-amber-800">
        {info?.detail ||
          'The scheduling engine found no bookable time for this request.'}
      </p>
      {reasonCode && (
        <p className="mt-2 font-mono text-[11px] text-amber-700">reasonCode: {reasonCode}</p>
      )}
      {onRetry && (
        <Button variant="secondary" size="sm" className="mt-3" onClick={onRetry}>
          Search again
        </Button>
      )}
    </div>
  );
}

export function ConflictList({ conflicts }) {
  if (!conflicts?.length) return null;
  return (
    <ul className="space-y-2">
      {conflicts.map((c, i) => (
        <li
          key={`${c.conflictType}-${i}`}
          className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-900"
        >
          <span className="font-semibold">
            {CONFLICT_TYPE_LABELS[c.conflictType] || c.conflictType}
          </span>
          {c.message && <span className="ml-1">— {c.message}</span>}
          {c.start && (
            <span className="mt-0.5 block text-red-700">
              {formatTimeRange(c.start, c.end)}
            </span>
          )}
        </li>
      ))}
    </ul>
  );
}

/**
 * Renders a SchedulingResponse — {slots, alternatives, reasonCode} — for either flow.
 * `interviewerNames` maps interviewerId -> display name when the caller has them.
 */
export function SchedulingResults({
  response,
  interviewerNames = {},
  selectedSlot,
  onSelectSlot,
  disabled = false,
  onRetry,
  footer,
}) {
  const [showAlternatives, setShowAlternatives] = useState(false);
  if (!response) return null;

  const slots = response.slots || response.recommendedSlots || [];
  const alternatives = response.alternatives || [];

  if (!slots.length && !alternatives.length) {
    return <NoSlotsState reasonCode={response.reasonCode} onRetry={onRetry} />;
  }

  const isSelected = (slot) =>
    selectedSlot &&
    selectedSlot.start === slot.start &&
    selectedSlot.end === slot.end &&
    selectedSlot.interviewerId === slot.interviewerId;

  return (
    <div className="space-y-3">
      {slots.length > 0 && (
        <>
          <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
            Recommended {slots.length === 1 ? 'slot' : 'slots'}
          </p>
          <div className="space-y-2">
            {slots.map((slot, i) => (
              <SlotRow
                key={`${slot.interviewerId}-${slot.start}`}
                slot={slot}
                rank={i}
                interviewerName={interviewerNames[slot.interviewerId]}
                selected={isSelected(slot)}
                onSelect={onSelectSlot}
                disabled={disabled || !onSelectSlot}
              />
            ))}
          </div>
        </>
      )}

      {alternatives.length > 0 && (
        <div>
          <button
            type="button"
            onClick={() => setShowAlternatives((v) => !v)}
            className="text-xs font-medium text-brand-700 hover:underline"
          >
            {showAlternatives ? 'Hide' : 'Show'} {alternatives.length} alternative
            {alternatives.length === 1 ? '' : 's'}
          </button>
          {showAlternatives && (
            <div className="mt-2 space-y-2">
              {alternatives.map((slot) => (
                <SlotRow
                  key={`alt-${slot.interviewerId}-${slot.start}`}
                  slot={slot}
                  rank={-1}
                  interviewerName={interviewerNames[slot.interviewerId]}
                  selected={isSelected(slot)}
                  onSelect={onSelectSlot}
                  disabled={disabled || !onSelectSlot}
                />
              ))}
            </div>
          )}
        </div>
      )}

      <InfoNote tone="info">
        A slot is only a proposal until the backend confirms the booking. Every booking
        re-checks availability and conflicts from scratch, so a slot listed here can
        still be refused if someone else takes it first.
      </InfoNote>

      {footer}
    </div>
  );
}

export function InterviewerMatchList({ match }) {
  const [showIneligible, setShowIneligible] = useState(false);
  if (!match) return null;
  const eligible = match.eligibleInterviewers || [];
  const ineligible = match.ineligibleInterviewers || [];

  return (
    <div className="space-y-3">
      {eligible.length === 0 ? (
        <EmptyState
          title="No interviewer qualifies for this round"
          detail="Required skills at the minimum proficiency are disqualifying; domain, primary-skill bonus and workload only affect ranking."
        />
      ) : (
        <ul className="space-y-2">
          {eligible.map((m) => (
            <li
              key={m.interviewerId}
              className="flex items-center justify-between rounded-md border border-slate-200 px-3 py-2"
            >
              <span className="text-sm text-slate-900">{m.name}</span>
              <Badge tone="green">score {Number(m.score ?? 0).toFixed(1)}</Badge>
            </li>
          ))}
        </ul>
      )}

      {ineligible.length > 0 && (
        <div>
          <button
            type="button"
            onClick={() => setShowIneligible((v) => !v)}
            className="text-xs font-medium text-brand-700 hover:underline"
          >
            {showIneligible ? 'Hide' : 'Why were'} {ineligible.length} interviewer
            {ineligible.length === 1 ? '' : 's'} excluded?
          </button>
          {showIneligible && (
            <ul className="mt-2 space-y-2">
              {ineligible.map((m) => (
                <li
                  key={m.interviewerId}
                  className="rounded-md border border-slate-200 bg-slate-50 px-3 py-2"
                >
                  <p className="text-sm font-medium text-slate-800">{m.name}</p>
                  <ul className="mt-1 list-inside list-disc text-xs text-slate-600">
                    {(m.failureReasons || []).map((r, i) => (
                      <li key={i}>{r}</li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
