import { useState } from 'react';
import { availabilityApi, integrationsApi } from '../api/endpoints';
import { useFetch } from '../hooks/useAsync';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../context/ToastContext';
import { PageHeader } from '../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState,
  Field, InfoNote, Input, LoadingState, Modal, Select,
} from '../components/ui';
import { Link } from 'react-router-dom';
import { browserTimezone, formatDate, timezoneOptions, toBackendTime, toTimeInput, todayInput } from '../utils/datetime';
import { parseApiError } from '../utils/errors';

/**
 * Availability is always self-managed — the backend refuses writes for anyone else.
 * These rows only feed the scheduling engine for users who have NOT connected Google
 * Calendar; once connected, real free/busy replaces them entirely.
 */
export default function Availability() {
  const { userId, timezone } = useAuth();
  const toast = useToast();
  const list = useFetch(() => availabilityApi.list(userId), [userId], { skip: !userId });
  const google = useFetch(() => integrationsApi.status(), []);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState(null);
  const [deleting, setDeleting] = useState(null);

  const connected = google.data?.connected && google.data?.activeProvider === 'google';

  const remove = async () => {
    try {
      await availabilityApi.remove(deleting.id);
      toast.success('Availability window removed.');
      setDeleting(null);
      list.reload();
    } catch (err) {
      toast.apiError(err);
    }
  };

  const rows = [...(list.data || [])].sort(
    (a, b) => new Date(a.date) - new Date(b.date) || a.startTime.localeCompare(b.startTime),
  );

  return (
    <>
      <PageHeader
        title="Your availability"
        subtitle="Windows the scheduler can book you into."
        actions={<Button onClick={() => setAdding(true)}>Add a window</Button>}
      />

      {connected && (
        <InfoNote tone="info" className="mb-5" title="Your calendar is connected — these are ignored">
          Because you connected Google Calendar, the scheduler uses your working hours minus your
          real busy time instead of these windows. You can leave this page empty. They&apos;re
          still saved, and would be used again if the connection went away.
        </InfoNote>
      )}

      {!connected && !google.loading && (
        <InfoNote tone="info" className="mb-5" title="Skip this page entirely">
          <Link to="/settings/integrations" className="font-medium underline">
            Connect Google Calendar
          </Link>{' '}
          and we&apos;ll find times automatically from your real free/busy — no need to set
          availability by hand.
        </InfoNote>
      )}

      {list.loading && <LoadingState />}
      {list.error && <ErrorState error={parseApiError(list.error)} onRetry={list.reload} />}

      {!list.loading && !list.error && (
        <Card>
          <CardHeader title="Windows" subtitle={`${rows.length} saved`} />
          <CardBody>
            {rows.length === 0 ? (
              <EmptyState
                icon="🗓️"
                title="No availability set"
                detail={
                  connected
                    ? "That's fine — your connected calendar is what the scheduler reads."
                    : "Until you add a window or connect your calendar, no slot can be found for you."
                }
                action={<Button size="sm" onClick={() => setAdding(true)}>Add a window</Button>}
              />
            ) : (
              <ul className="space-y-2">
                {rows.map((a) => (
                  <li
                    key={a.id}
                    className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 px-4 py-3"
                  >
                    <div>
                      <p className="text-sm font-medium text-slate-900">{formatDate(a.date)}</p>
                      <p className="text-xs text-slate-600">
                        {toTimeInput(a.startTime)} – {toTimeInput(a.endTime)}
                        <span className="ml-1 text-slate-400">({a.timezone})</span>
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge tone={a.status === 'AVAILABLE' ? 'green' : 'slate'}>
                        {a.status === 'AVAILABLE' ? 'Available' : 'Unavailable'}
                      </Badge>
                      <Button variant="ghost" size="sm" onClick={() => setEditing(a)}>
                        Edit
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => setDeleting(a)}>
                        Delete
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </CardBody>
        </Card>
      )}

      <AvailabilityModal
        open={adding || !!editing}
        existing={editing}
        defaultTimezone={timezone || browserTimezone()}
        onClose={() => {
          setAdding(false);
          setEditing(null);
        }}
        onSaved={() => {
          setAdding(false);
          setEditing(null);
          list.reload();
        }}
      />

      <Modal
        open={!!deleting}
        title="Remove this window?"
        onClose={() => setDeleting(null)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setDeleting(null)}>
              Keep it
            </Button>
            <Button variant="danger" onClick={remove}>
              Remove
            </Button>
          </>
        }
      >
        <p className="text-sm text-slate-700">
          Removing a window narrows when you can be booked. Interviews already scheduled inside it
          are unaffected.
        </p>
      </Modal>
    </>
  );
}

function AvailabilityModal({ open, existing, defaultTimezone, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState(() => ({
    date: todayInput(),
    startTime: '09:00',
    endTime: '17:00',
    status: 'AVAILABLE',
    timezone: defaultTimezone,
  }));
  const [submitting, setSubmitting] = useState(false);
  const [loadedFor, setLoadedFor] = useState(null);

  // Seed the form from the row being edited, once per row.
  if (existing && loadedFor !== existing.id) {
    setLoadedFor(existing.id);
    setForm({
      date: existing.date,
      startTime: toTimeInput(existing.startTime),
      endTime: toTimeInput(existing.endTime),
      status: existing.status,
      timezone: existing.timezone,
    });
  }
  if (!existing && loadedFor !== null) setLoadedFor(null);

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    const body = {
      date: form.date,
      startTime: toBackendTime(form.startTime),
      endTime: toBackendTime(form.endTime),
      status: form.status,
      timezone: form.timezone,
    };
    try {
      if (existing) await availabilityApi.update(existing.id, body);
      else await availabilityApi.create(body);
      toast.success(existing ? 'Window updated.' : 'Availability added.');
      onSaved();
    } catch (err) {
      const parsed = parseApiError(err);
      // The backend validates AVAILABLE windows against working hours, the weekend
      // policy, and overlap with your existing windows.
      toast.error(parsed.message, {
        title: parsed.status === 409 ? 'Overlaps an existing window' : undefined,
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={existing ? 'Edit availability' : 'Add availability'}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button form="availability-form" type="submit" loading={submitting}>
            {existing ? 'Save changes' : 'Add window'}
          </Button>
        </>
      }
    >
      <form id="availability-form" onSubmit={submit} className="space-y-4">
        <Field label="Date" required>
          <Input type="date" required value={form.date} onChange={set('date')} />
        </Field>
        <div className="grid grid-cols-2 gap-4">
          <Field label="From" required>
            <Input type="time" required value={form.startTime} onChange={set('startTime')} />
          </Field>
          <Field label="To" required>
            <Input type="time" required value={form.endTime} onChange={set('endTime')} />
          </Field>
        </div>
        <Field
          label="Status"
          hint="Available windows are checked against working hours; unavailable ones only block time."
        >
          <Select value={form.status} onChange={set('status')}>
            <option value="AVAILABLE">Available — can be booked</option>
            <option value="UNAVAILABLE">Unavailable — block this time</option>
          </Select>
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
      </form>
    </Modal>
  );
}
