/** SCREAMING_SNAKE -> "Screaming snake", for any enum without a nicer label. */
export function humanize(value) {
  if (!value) return '';
  const s = String(value).replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function initials(name) {
  if (!name) return '?';
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() || '')
    .join('');
}

export function shortId(id) {
  return id ? String(id).slice(0, 8) : '—';
}

export function pluralize(n, singular, plural) {
  return `${n} ${n === 1 ? singular : plural || `${singular}s`}`;
}

/** Counts occurrences of `key` across `rows` — feeds the client-side admin charts. */
export function countBy(rows, key) {
  const out = {};
  for (const row of rows || []) {
    const k = typeof key === 'function' ? key(row) : row?.[key];
    if (k == null) continue;
    out[k] = (out[k] || 0) + 1;
  }
  return out;
}

/** Case-insensitive substring match across the given fields. */
export function matchesQuery(row, query, fields) {
  if (!query) return true;
  const q = query.trim().toLowerCase();
  return fields.some((f) => String(row?.[f] ?? '').toLowerCase().includes(q));
}
