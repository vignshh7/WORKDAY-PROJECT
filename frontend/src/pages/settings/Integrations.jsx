import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { integrationsApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { useToast } from '../../context/ToastContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, CardHeader, DescriptionList,
  ErrorState, InfoNote, LoadingState,
} from '../../components/ui';
import { formatDateTime } from '../../utils/datetime';
import { parseApiError } from '../../utils/errors';

// This page MUST live at /settings/integrations: the backend's OAuth callback
// redirects the browser to {APP_BASE_URL}/settings/integrations?google=connected
// (or ?google=error&message=...). Changing the path silently breaks the round trip.

export default function Integrations() {
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const [connecting, setConnecting] = useState(false);
  const { data, error, loading, reload } = useFetch(() => integrationsApi.status(), []);

  // Read the callback's query params once on mount, toast, then strip them so a
  // refresh doesn't replay the message.
  useEffect(() => {
    const result = params.get('google');
    if (!result) return;
    if (result === 'connected') {
      toast.success('Google Calendar connected.');
      reload();
    } else {
      toast.error(params.get('message') || 'Google Calendar connection failed.', {
        title: 'Connection failed',
      });
    }
    const next = new URLSearchParams(params);
    next.delete('google');
    next.delete('message');
    setParams(next, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const connect = async () => {
    setConnecting(true);
    try {
      const { authorizationUrl } = await integrationsApi.authorizeUrl();
      // Full navigation, not a fetch — Google's consent screen owns the browser here.
      window.location.assign(authorizationUrl);
    } catch (err) {
      const parsed = parseApiError(err);
      // 502 = the backend has no GOOGLE_CLIENT_ID/SECRET configured yet.
      toast.error(
        parsed.status === 502
          ? 'Google Calendar is not configured on the server yet. Ask an administrator to set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and CALENDAR_PROVIDER=google.'
          : parsed.message,
        { title: 'Could not start the connection' },
      );
      setConnecting(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Settings & integrations"
        subtitle="Connect your own calendar so the scheduler can see when you're free."
      />

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          <Card>
            <CardHeader
              title="Google Calendar"
              subtitle="Per-user connection — every participant connects their own account."
              action={
                loading ? null : data?.connected ? (
                  <Badge tone="green">Connected</Badge>
                ) : (
                  <Badge tone="slate">Not connected</Badge>
                )
              }
            />
            <CardBody className="space-y-4">
              {loading && <LoadingState label="Checking your connection…" />}
              {error && <ErrorState error={parseApiError(error)} onRetry={reload} />}

              {!loading && !error && (
                <>
                  <DescriptionList
                    items={[
                      { label: 'Status', value: data?.connected ? 'Connected' : 'Not connected' },
                      { label: 'Calendar provider', value: data?.activeProvider || '—' },
                      data?.connectedEmail && {
                        label: 'Connected account',
                        value: data.connectedEmail,
                      },
                      data?.expiresAt && {
                        label: 'Token expires',
                        value: formatDateTime(data.expiresAt),
                      },
                    ]}
                  />

                  <div className="flex flex-wrap gap-2">
                    <Button onClick={connect} loading={connecting}>
                      {data?.connected ? 'Reconnect Google Calendar' : 'Connect Google Calendar'}
                    </Button>
                    <Button variant="secondary" onClick={reload}>
                      Refresh status
                    </Button>
                  </div>

                  {data?.activeProvider && data.activeProvider !== 'google' && (
                    <InfoNote tone="warning" title="Calendar integration is off">
                      The server is running with <code>CALENDAR_PROVIDER={data.activeProvider}</code>,
                      so calendar operations are no-ops. Connecting here won&apos;t create real
                      events until the server is switched to <code>google</code>.
                    </InfoNote>
                  )}

                  {/* Documented backend gap — stated, not faked with a dead button. */}
                  <InfoNote tone="gap" title="No disconnect yet">
                    The backend has no disconnect endpoint today, so there&apos;s no way to
                    revoke the connection from here. Revoke access from your Google account&apos;s
                    security settings instead.
                  </InfoNote>
                </>
              )}
            </CardBody>
          </Card>
        </div>

        <div className="space-y-5">
          <Card>
            <CardHeader title="Why connect?" />
            <CardBody className="space-y-3 text-sm text-slate-600">
              <p>
                Connect your calendar and we&apos;ll find times automatically — no need to set
                availability by hand.
              </p>
              <p>
                Once connected, your bookable time is your working hours in your own timezone,
                minus whatever Google reports as busy. Any availability you entered manually
                stops being consulted for scheduling.
              </p>
              <p>
                If you don&apos;t connect, the scheduler falls back to the availability windows
                you enter yourself.
              </p>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Invitations & Meet links" />
            <CardBody className="space-y-3 text-sm text-slate-600">
              <p>
                When an interview is booked, Google sends its own calendar invitation — with the
                Meet link — to every participant&apos;s email address directly. That happens
                whether or not you personally connected your calendar here.
              </p>
              <p className="text-xs text-slate-500">
                The Meet link isn&apos;t returned on the booking response, so it isn&apos;t shown
                on the interview page — check your calendar invitation for it.
              </p>
            </CardBody>
          </Card>
        </div>
      </div>
    </>
  );
}
