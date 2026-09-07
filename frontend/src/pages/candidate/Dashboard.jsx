import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { candidatesApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { useAuth } from '../../auth/AuthContext';
import { useToast } from '../../context/ToastContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState,
  Field, InfoNote, Input, LoadingState,
} from '../../components/ui';
import { CandidateStatusBadge } from '../../components/StatusBadge';
import { PipelineEntryCard } from '../../components/Pipeline';
import { parseApiError } from '../../utils/errors';

// Backend reality: GET /api/candidates is staff-only and nothing maps a userId to a
// candidate profile id, so a signed-in candidate can't discover their own candidate id
// through the API. Everything downstream (/pipeline, /skills, /status) needs that id.
//
// Rather than fake it, the id is asked for once and kept in this browser. The gap is
// stated plainly so it reads as a missing endpoint, not a quirk of the UI.

const CANDIDATE_ID_KEY = 'is.candidateId.';

function readStoredId(userId) {
  try {
    return localStorage.getItem(CANDIDATE_ID_KEY + userId);
  } catch {
    return null;
  }
}

function storeId(userId, value) {
  try {
    if (value) localStorage.setItem(CANDIDATE_ID_KEY + userId, value);
    else localStorage.removeItem(CANDIDATE_ID_KEY + userId);
  } catch {
    // Storage blocked — the id lives only for this page's lifetime.
  }
}

/** Resolves the signed-in candidate's profile id, asking for it if the API can't. */
export function useMyCandidateId() {
  const { userId, roleProfile } = useAuth();
  // roleProfile is populated only when the account can list candidates (staff).
  const [manualId, setManualId] = useState(() => readStoredId(userId));
  const candidateId = roleProfile?.id || manualId || null;

  const save = useCallback(
    (value) => {
      storeId(userId, value);
      setManualId(value);
    },
    [userId],
  );

  const clear = useCallback(() => {
    storeId(userId, null);
    setManualId(null);
  }, [userId]);

  return { candidateId, save, clear, isManual: !roleProfile?.id && !!manualId };
}

export function CandidateIdPrompt({ onSaved }) {
  const toast = useToast();
  const { save } = useMyCandidateId();
  const [value, setValue] = useState('');
  const [checking, setChecking] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setChecking(true);
    try {
      // Verify before storing — a wrong id would 403/404 on every page after this.
      const profile = await candidatesApi.get(value.trim());
      save(value.trim());
      toast.success(`Linked to ${profile.name}'s profile.`);
      onSaved?.();
    } catch (err) {
      const parsed = parseApiError(err);
      toast.error(
        parsed.status === 403
          ? "That profile belongs to someone else. Use the candidate ID your recruiter gave you."
          : parsed.status === 404
            ? 'No candidate profile with that ID.'
            : parsed.message,
      );
    } finally {
      setChecking(false);
    }
  };

  return (
    <Card className="mx-auto max-w-xl">
      <CardHeader title="Link your candidate profile" />
      <CardBody className="space-y-4">
        <InfoNote tone="gap" title="Why you have to do this">
          The backend has no endpoint that finds a candidate profile from a signed-in user, and
          listing candidates is restricted to recruiters. Until one exists, paste the candidate ID
          your recruiter gave you — we&apos;ll remember it in this browser.
        </InfoNote>
        <form onSubmit={submit} className="space-y-4">
          <Field label="Candidate ID" required>
            <Input
              required
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder="00000000-0000-0000-0000-000000000000"
            />
          </Field>
          <Button type="submit" loading={checking} className="w-full">
            Link my profile
          </Button>
        </form>
      </CardBody>
    </Card>
  );
}

export default function CandidateDashboard() {
  const { name } = useAuth();
  const { candidateId, clear, isManual } = useMyCandidateId();
  const [, force] = useState(0);

  const profile = useFetch(() => candidatesApi.get(candidateId), [candidateId], {
    skip: !candidateId,
  });
  const pipeline = useFetch(() => candidatesApi.pipeline(candidateId), [candidateId], {
    skip: !candidateId,
  });
  const skills = useFetch(() => candidatesApi.skills(candidateId), [candidateId], {
    skip: !candidateId,
  });

  if (!candidateId) {
    return (
      <>
        <PageHeader title={`Welcome, ${name}`} />
        <CandidateIdPrompt onSaved={() => force((n) => n + 1)} />
      </>
    );
  }

  if (profile.loading) return <LoadingState label="Loading your profile…" />;
  if (profile.error) {
    return (
      <>
        <ErrorState error={parseApiError(profile.error)} onRetry={profile.reload} />
        {isManual && (
          <div className="mt-4 text-center">
            <Button variant="secondary" size="sm" onClick={clear}>
              Use a different candidate ID
            </Button>
          </div>
        )}
      </>
    );
  }

  const me = profile.data;
  const entries = pipeline.data || [];

  return (
    <>
      <PageHeader
        title={`Welcome, ${me.name}`}
        subtitle="Where you are, and what's coming up."
        actions={
          <Link to="/settings/integrations">
            <Button variant="secondary">Connect Google Calendar</Button>
          </Link>
        }
      />

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          <Card>
            <CardHeader title="Your pipeline" subtitle="Every round, and where it stands." />
            <CardBody className="space-y-4">
              {pipeline.loading && <LoadingState />}
              {pipeline.error && (
                <ErrorState error={parseApiError(pipeline.error)} onRetry={pipeline.reload} />
              )}
              {!pipeline.loading && !pipeline.error && entries.length === 0 && (
                <EmptyState
                  icon="🧭"
                  title="No interview process yet"
                  detail="Once a recruiter starts a process for you, your rounds appear here."
                />
              )}
              {entries.map((entry) => (
                <PipelineEntryCard
                  key={entry.process.id}
                  entry={entry}
                  renderRoundActions={(round) =>
                    ['SCHEDULED', 'RESCHEDULE_REQUIRED', 'PENDING'].includes(round.status) ? (
                      <Link to={`/interviews/${round.id}`}>
                        <Button variant="secondary" size="sm">
                          Open
                        </Button>
                      </Link>
                    ) : null
                  }
                />
              ))}
            </CardBody>
          </Card>
        </div>

        <div className="space-y-5">
          <Card>
            <CardHeader
              title="Your status"
              action={<CandidateStatusBadge status={me.currentStatus} />}
            />
            <CardBody className="space-y-3 text-sm text-slate-600">
              <p>
                This is your overall position in the pipeline. It only moves when an interviewer&apos;s
                result is submitted — rescheduling or cancelling a round never changes it.
              </p>
              <div className="flex flex-wrap gap-2 pt-1">
                <Badge tone="slate">{me.email}</Badge>
                {me.phone && <Badge tone="slate">{me.phone}</Badge>}
              </div>
              {isManual && (
                <Button variant="ghost" size="sm" onClick={clear}>
                  Unlink this profile
                </Button>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Your skills"
              subtitle="What interviewer matching compares against a job's requirements."
            />
            <CardBody>
              {skills.loading && <LoadingState label="Loading skills…" />}
              {skills.error && (
                <ErrorState error={parseApiError(skills.error)} onRetry={skills.reload} />
              )}
              {!skills.loading && !skills.error && (skills.data?.length ? (
                <ul className="space-y-2">
                  {skills.data.map((s) => (
                    <li
                      key={s.id}
                      className="flex items-center justify-between rounded-md border border-slate-200 px-3 py-2"
                    >
                      <span className="text-sm text-slate-800">{s.skillName}</span>
                      <span className="flex items-center gap-2">
                        <Badge tone="blue">level {s.proficiency}</Badge>
                        <span className="text-xs text-slate-500">{s.yearsExperience}y</span>
                      </span>
                    </li>
                  ))}
                </ul>
              ) : (
                <EmptyState
                  icon="🎯"
                  title="No skills recorded"
                  detail="Ask your recruiter to add skills to your profile."
                />
              ))}
            </CardBody>
          </Card>

          <InfoNote tone="info" title="Make scheduling easy">
            Connect your Google Calendar and we&apos;ll find times around your real commitments —
            no need to fill in availability by hand.
          </InfoNote>
        </div>
      </div>
    </>
  );
}
