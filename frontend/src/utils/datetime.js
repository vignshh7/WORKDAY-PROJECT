// The backend speaks ISO-8601 offset date-times ("2026-09-15T14:00:00+05:30") for
// instants, plain "YYYY-MM-DD" for dates, and "HH:mm:ss" for times. Keep those shapes
// exact — a Date object stringified the wrong way is a silent 400.

export function browserTimezone() {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/** "2026-09-15" for a Date, in local time — toISOString would shift the day. */
export function toDateInput(date) {
  const d = date instanceof Date ? date : new Date(date);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function todayInput() {
  return toDateInput(new Date());
}

export function daysFromToday(n) {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return toDateInput(d);
}

/** "14:00" from an <input type="time"> -> "14:00:00" as the backend's LocalTime. */
export function toBackendTime(value) {
  if (!value) return undefined;
  return value.length === 5 ? `${value}:00` : value;
}

/** "14:00:00" -> "14:00" for an <input type="time">. */
export function toTimeInput(value) {
  return value ? value.slice(0, 5) : '';
}

/**
 * Render a backend instant. `tz` renders it in a specific zone (an interview's own
 * timezone); omitting it renders in the viewer's local zone.
 */
export function formatDateTime(iso, tz) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const opts = {
    weekday: 'short', month: 'short', day: 'numeric',
    hour: 'numeric', minute: '2-digit',
  };
  if (tz) opts.timeZone = tz;
  return new Intl.DateTimeFormat(undefined, opts).format(d);
}

export function formatTimeRange(startIso, endIso, tz) {
  if (!startIso) return '—';
  const start = new Date(startIso);
  const end = endIso ? new Date(endIso) : null;
  const dateOpts = { weekday: 'short', month: 'short', day: 'numeric' };
  const timeOpts = { hour: 'numeric', minute: '2-digit' };
  if (tz) {
    dateOpts.timeZone = tz;
    timeOpts.timeZone = tz;
  }
  const datePart = new Intl.DateTimeFormat(undefined, dateOpts).format(start);
  const startPart = new Intl.DateTimeFormat(undefined, timeOpts).format(start);
  if (!end) return `${datePart}, ${startPart}`;
  const endPart = new Intl.DateTimeFormat(undefined, timeOpts).format(end);
  return `${datePart}, ${startPart} – ${endPart}`;
}

export function formatDate(value, tz) {
  if (!value) return '—';
  // A bare "YYYY-MM-DD" parses as UTC midnight; read the parts directly so it never
  // renders as the previous day west of Greenwich.
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const [y, m, d] = value.split('-').map(Number);
    return new Intl.DateTimeFormat(undefined, {
      year: 'numeric', month: 'short', day: 'numeric',
    }).format(new Date(y, m - 1, d));
  }
  const opts = { year: 'numeric', month: 'short', day: 'numeric' };
  if (tz) opts.timeZone = tz;
  return new Intl.DateTimeFormat(undefined, opts).format(new Date(value));
}

/** "in 3 hours" / "2 days ago" — notification timestamps, interview countdowns. */
export function relativeTime(iso) {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const diffSec = Math.round((then - Date.now()) / 1000);
  const abs = Math.abs(diffSec);
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  if (abs < 60) return rtf.format(diffSec, 'second');
  if (abs < 3600) return rtf.format(Math.round(diffSec / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(diffSec / 3600), 'hour');
  if (abs < 2592000) return rtf.format(Math.round(diffSec / 86400), 'day');
  return rtf.format(Math.round(diffSec / 2592000), 'month');
}

export function isFuture(iso) {
  return !!iso && new Date(iso).getTime() > Date.now();
}

export function durationMinutes(startIso, endIso) {
  if (!startIso || !endIso) return null;
  return Math.round((new Date(endIso) - new Date(startIso)) / 60000);
}

/** Common IANA zones for the timezone picker, with the browser's own zone first. */
export function timezoneOptions() {
  const common = [
    'Asia/Kolkata', 'America/New_York', 'America/Los_Angeles', 'America/Chicago',
    'Europe/London', 'Europe/Berlin', 'Asia/Singapore', 'Asia/Tokyo',
    'Australia/Sydney', 'UTC',
  ];
  const local = browserTimezone();
  return [local, ...common.filter((z) => z !== local)];
}
