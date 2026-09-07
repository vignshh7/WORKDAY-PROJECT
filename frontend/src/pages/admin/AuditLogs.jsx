import { useState } from 'react';
import { auditApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, EmptyState, ErrorState, Field,
  Input, LoadingState, Select,
} from '../../components/ui';
import { formatDateTime, relativeTime } from '../../utils/datetime';
import { humanize, shortId } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

// The only admin view with real, backend-aggregated data behind it. Every filter here
// is a query param the endpoint actually supports.

const ENTITY_TYPES = [
  'INTERVIEW_ROUND', 'INTERVIEW_PROCESS', 'CANDIDATE', 'JOB', 'USER',
  'INTERVIEWER', 'AVAILABILITY', 'NOTIFICATION',
];

export default function AuditLogs() {
  const [filters, setFilters] = useState({
    actorId: '', action: '', entityType: '', entityId: '',
    dateFrom: '', dateTo: '', limit: 200,
  });
  const [applied, setApplied] = useState({ limit: 200 });

  const { data, error, loading, reload } = useFetch(() => auditApi.list(applied), [applied]);
  const set = (k) => (e) => setFilters((f) => ({ ...f, [k]: e.target.value }));

  const apply = (e) => {
    e.preventDefault();
    const params = { limit: Number(filters.limit) || 200 };
    for (const key of ['actorId', 'action', 'entityType', 'entityId']) {
      if (filters[key].trim()) params[key] = filters[key].trim();
    }
    // The endpoint wants ISO-8601 offset date-times, not bare dates.
    if (filters.dateFrom) params.dateFrom = new Date(`${filters.dateFrom}T00:00:00`).toISOString();
    if (filters.dateTo) params.dateTo = new Date(`${filters.dateTo}T23:59:59`).toISOString();
    setApplied(params);
  };

  const reset = () => {
    setFilters({
      actorId: '', action: '', entityType: '', entityId: '',
      dateFrom: '', dateTo: '', limit: 200,
    });
    setApplied({ limit: 200 });
  };

  const rows = data || [];

  return (
    <>
      <PageHeader
        title="Audit logs"
        subtitle="Every recorded action, human and system-triggered."
        actions={
          <Button variant="secondary" onClick={reload}>
            Refresh
          </Button>
        }
      />

      <Card className="mb-4">
        <CardBody>
          <form onSubmit={apply} className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="Action">
              <Input value={filters.action} onChange={set('action')} placeholder="BOOK_INTERVIEW" />
            </Field>
            <Field label="Entity type">
              <Select value={filters.entityType} onChange={set('entityType')}>
                <option value="">Any</option>
                {ENTITY_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {humanize(t)}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Entity ID">
              <Input value={filters.entityId} onChange={set('entityId')} placeholder="UUID" />
            </Field>
            <Field label="Actor ID">
              <Input value={filters.actorId} onChange={set('actorId')} placeholder="UUID" />
            </Field>
            <Field label="From">
              <Input type="date" value={filters.dateFrom} onChange={set('dateFrom')} />
            </Field>
            <Field label="To">
              <Input type="date" value={filters.dateTo} onChange={set('dateTo')} />
            </Field>
            <Field label="Limit">
              <Input type="number" min={1} max={1000} value={filters.limit} onChange={set('limit')} />
            </Field>
            <div className="flex items-end gap-2">
              <Button type="submit" loading={loading} className="flex-1">
                Apply
              </Button>
              <Button type="button" variant="secondary" onClick={reset}>
                Reset
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>

      {loading && <LoadingState />}
      {error && <ErrorState error={parseApiError(error)} onRetry={reload} />}

      {!loading && !error && (
        <Card>
          <CardBody>
            {rows.length === 0 ? (
              <EmptyState
                icon="📜"
                title="No audit entries match"
                detail="Widen the date range or clear the filters."
              />
            ) : (
              <ol className="relative space-y-1 border-l border-slate-200 pl-5">
                {rows.map((log) => (
                  <li key={log.id} className="relative py-2">
                    <span
                      className={`absolute -left-[1.4rem] top-3.5 h-2 w-2 rounded-full ${
                        log.actorType === 'SYSTEM' ? 'bg-slate-400' : 'bg-brand-500'
                      }`}
                    />
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-xs font-semibold text-slate-900">
                        {log.action}
                      </span>
                      <Badge tone={log.actorType === 'SYSTEM' ? 'slate' : 'blue'}>
                        {log.actorType === 'SYSTEM' ? 'System' : `Actor ${shortId(log.actorId)}`}
                      </Badge>
                      {log.entityType && (
                        <span className="text-xs text-slate-500">
                          {humanize(log.entityType)} {shortId(log.entityId)}
                        </span>
                      )}
                    </div>
                    <p className="mt-0.5 text-xs text-slate-500">
                      {formatDateTime(log.createdAt)} · {relativeTime(log.createdAt)}
                    </p>
                    {log.metadata && (
                      <details className="mt-1">
                        <summary className="cursor-pointer text-xs text-brand-700 hover:underline">
                          Metadata
                        </summary>
                        <pre className="mt-1 overflow-x-auto rounded-md bg-slate-50 p-3 text-[11px] text-slate-700">
                          {typeof log.metadata === 'string'
                            ? log.metadata
                            : JSON.stringify(log.metadata, null, 2)}
                        </pre>
                      </details>
                    )}
                  </li>
                ))}
              </ol>
            )}
          </CardBody>
        </Card>
      )}
    </>
  );
}
