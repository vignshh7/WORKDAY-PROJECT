import { useEffect, useMemo, useState } from 'react';
import { notificationsApi } from '../api/endpoints';
import { useFetch } from '../hooks/useAsync';
import { useAuth } from '../auth/AuthContext';
import { PageHeader } from '../layouts/AppLayout';
import { Card, EmptyState, ErrorState, InfoNote, LoadingState, Select } from '../components/ui';
import { NotificationStatusBadge } from '../components/StatusBadge';
import { countNew, readLastSeen, writeLastSeen } from '../components/NotificationBell';
import { NOTIFICATION_META } from '../constants/enums';
import { formatDateTime, relativeTime } from '../utils/datetime';
import { parseApiError } from '../utils/errors';

export default function Notifications() {
  const { userId } = useAuth();
  const { data, error, loading, reload } = useFetch(() => notificationsApi.list(), []);
  const [typeFilter, setTypeFilter] = useState('');
  // Snapshot the "last seen" mark before this visit overwrites it, so the items that
  // were new *when the page opened* stay highlighted while it's open.
  const [seenAtEntry] = useState(() => readLastSeen(userId));

  useEffect(() => {
    if (!loading && data) writeLastSeen(userId);
  }, [loading, data, userId]);

  const items = useMemo(() => data || [], [data]);
  const newCount = useMemo(() => countNew(items, seenAtEntry), [items, seenAtEntry]);
  const types = useMemo(() => [...new Set(items.map((n) => n.type))].sort(), [items]);
  const visible = typeFilter ? items.filter((n) => n.type === typeFilter) : items;

  return (
    <>
      <PageHeader
        title="Notifications"
        subtitle={
          newCount > 0
            ? `${newCount} new since your last visit`
            : 'Everything sent to you, newest first.'
        }
        actions={
          types.length > 1 && (
            <Select
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              className="w-56"
            >
              <option value="">All types</option>
              {types.map((t) => (
                <option key={t} value={t}>
                  {NOTIFICATION_META[t]?.label || t}
                </option>
              ))}
            </Select>
          )
        }
      />

      {loading && <LoadingState />}
      {error && <ErrorState error={parseApiError(error)} onRetry={reload} />}

      {!loading && !error && (
        <div className="space-y-4">
          {/* The backend has no read/unread column — say what "new" actually means. */}
          <InfoNote tone="gap">
            &ldquo;New&rdquo; is tracked in this browser as anything that arrived since you last
            opened this page. The server doesn&apos;t track read state, so this count won&apos;t
            follow you to another device.
          </InfoNote>

          {visible.length === 0 ? (
            <EmptyState
              icon="🔔"
              title={typeFilter ? 'No notifications of that type' : 'No notifications yet'}
              detail="Scheduling, cancellation and reminder events will show up here."
            />
          ) : (
            <Card className="divide-y divide-slate-100">
              {visible.map((n) => {
                const meta = NOTIFICATION_META[n.type] || { icon: '•', label: n.type };
                const isNew = seenAtEntry && new Date(n.createdAt) > new Date(seenAtEntry);
                return (
                  <div key={n.id} className={`flex gap-4 px-5 py-4 ${isNew ? 'bg-brand-50/40' : ''}`}>
                    <span className="text-xl leading-none">{meta.icon}</span>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <p className="text-sm font-medium text-slate-900">{meta.label}</p>
                        {isNew && (
                          <span className="rounded-full bg-brand-600 px-1.5 text-[10px] font-bold uppercase text-white">
                            New
                          </span>
                        )}
                        <NotificationStatusBadge status={n.status} />
                      </div>
                      <p className="mt-0.5 text-xs text-slate-500">
                        {formatDateTime(n.createdAt)} · {relativeTime(n.createdAt)}
                        {n.channel && <span className="ml-1">· {n.channel.toLowerCase()}</span>}
                      </p>
                      {n.status === 'FAILED' && (
                        <p className="mt-1 text-xs text-red-700">
                          Delivery failed — this is recorded after the fact and doesn&apos;t affect
                          the interview itself.
                        </p>
                      )}
                    </div>
                  </div>
                );
              })}
            </Card>
          )}
        </div>
      )}
    </>
  );
}
