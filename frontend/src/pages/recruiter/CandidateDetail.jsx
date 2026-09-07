import { useCallback, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { candidatesApi, jobsApi, processesApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { useToast } from '../../context/ToastContext';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, CardHeader, DescriptionList, EmptyState,
  ErrorState, Field, InfoNote, Input, LoadingState, Modal, Select,
} from '../../components/ui';
import { CandidateStatusBadge } from '../../components/StatusBadge';
import { PipelineEntryCard } from '../../components/Pipeline';
import { CANDIDATE_TERMINAL_STATUSES } from '../../constants/enums';
import { parseApiError } from '../../utils/errors';

export default function CandidateDetail() {
  const { id } = useParams();
  const toast = useToast();
  const { role } = useAuth();
  const isStaff = role === 'RECRUITER' || role === 'ADMIN';

  const candidate = useFetch(() => candidatesApi.get(id), [id]);
  const skills = useFetch(() => candidatesApi.skills(id), [id]);
  const pipeline = useFetch(() => candidatesApi.pipeline(id), [id]);

  const [startingProcess, setStartingProcess] = useState(false);
  const [addingSkill, setAddingSkill] = useState(false);
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);

  const reloadAll = useCallback(() => {
    candidate.reload();
    pipeline.reload();
  }, [candidate, pipeline]);

  if (candidate.loading) return <LoadingState />;
  if (candidate.error) {
    return <ErrorState error={parseApiError(candidate.error)} onRetry={candidate.reload} />;
  }

  const c = candidate.data;
  const entries = pipeline.data || [];
  const activeEntry = entries.find((e) => e.process.status === 'ACTIVE');
  // A terminal candidate gets no scheduling affordances. The backend refuses these too,
  // but greying them out is clearer than letting someone click into an error.
  const isTerminal = CANDIDATE_TERMINAL_STATUSES.includes(c.currentStatus);

  const withdraw = async () => {
    setWithdrawing(true);
    try {
      await candidatesApi.withdraw(id);
      toast.success('Candidate withdrawn. Every future round was cancelled.');
      setWithdrawOpen(false);
      reloadAll();
    } catch (err) {
      toast.apiError(err);
    } finally {
      setWithdrawing(false);
    }
  };

  return (
    <>
      <PageHeader
        breadcrumb={
          <Link to="/recruiter/candidates" className="hover:underline">
            ← Candidates
          </Link>
        }
        title={c.name}
        subtitle={c.email}
        actions={
          isStaff && (
            <>
              {!activeEntry && !isTerminal && (
                <Button onClick={() => setStartingProcess(true)}>Start interview process</Button>
              )}
              {activeEntry && !isTerminal && (
                <Link to={`/recruiter/scheduling?candidateId=${c.id}`}>
                  <Button>Schedule a round</Button>
                </Link>
              )}
              {!isTerminal && (
                <Button variant="danger" onClick={() => setWithdrawOpen(true)}>
                  Withdraw
                </Button>
              )}
            </>
          )
        }
      />

      {isTerminal && (
        <InfoNote tone="warning" className="mb-5" title={`This candidate is ${c.currentStatus.toLowerCase()}`}>
          {c.currentStatus === 'REJECTED' &&
            'Scheduling actions are unavailable. A FAIL result on a round rejects the candidate and cancels every later round.'}
          {c.currentStatus === 'WITHDRAWN' &&
            'The candidate withdrew from the pipeline and all future rounds were cancelled.'}
          {c.currentStatus === 'SELECTED' && 'The candidate cleared every round.'}
        </InfoNote>
      )}

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          <Card>
            <CardHeader
              title="Pipeline"
              subtitle="Every process this candidate has had, with all of its rounds."
            />
            <CardBody className="space-y-4">
              {pipeline.loading && <LoadingState label="Loading pipeline…" />}
              {pipeline.error && (
                <ErrorState error={parseApiError(pipeline.error)} onRetry={pipeline.reload} />
              )}
              {!pipeline.loading && !pipeline.error && entries.length === 0 && (
                <EmptyState
                  icon="🧭"
                  title="No interview process yet"
                  detail="Starting a process creates the four rounds: screening, technical, managerial, HR."
                  action={
                    isStaff && !isTerminal ? (
                      <Button size="sm" onClick={() => setStartingProcess(true)}>
                        Start interview process
                      </Button>
                    ) : null
                  }
                />
              )}
              {entries.map((entry) => (
                <PipelineEntryCard
                  key={entry.process.id}
                  entry={entry}
                  renderRoundActions={(round) => (
                    <Link to={`/interviews/${round.id}`}>
                      <Button variant="secondary" size="sm">
                        Open
                      </Button>
                    </Link>
                  )}
                />
              ))}
            </CardBody>
          </Card>
        </div>

        <div className="space-y-5">
          <Card>
            <CardHeader title="Profile" action={<CandidateStatusBadge status={c.currentStatus} />} />
            <CardBody>
              <DescriptionList
                className="sm:grid-cols-1"
                items={[
                  { label: 'Email', value: c.email },
                  { label: 'Phone', value: c.phone || '—' },
                  {
                    label: 'Resume',
                    value: c.resumeUrl ? (
                      <a
                        href={c.resumeUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="text-brand-700 hover:underline"
                      >
                        Open resume
                      </a>
                    ) : (
                      '—'
                    ),
                  },
                  { label: 'Candidate ID', value: <code className="text-xs">{c.id}</code> },
                ]}
              />
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Skills"
              action={
                <Button variant="secondary" size="sm" onClick={() => setAddingSkill(true)}>
                  Add
                </Button>
              }
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
                  detail="Interviewer matching compares these against the job's required skills."
                />
              ))}
            </CardBody>
          </Card>
        </div>
      </div>

      <StartProcessModal
        open={startingProcess}
        candidateId={c.id}
        onClose={() => setStartingProcess(false)}
        onCreated={() => {
          setStartingProcess(false);
          toast.success('Interview process created with four rounds.');
          reloadAll();
        }}
      />

      <AddSkillModal
        open={addingSkill}
        candidateId={c.id}
        onClose={() => setAddingSkill(false)}
        onAdded={() => {
          setAddingSkill(false);
          toast.success('Skill added.');
          skills.reload();
        }}
      />

      <Modal
        open={withdrawOpen}
        title="Withdraw this candidate?"
        onClose={() => setWithdrawOpen(false)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setWithdrawOpen(false)}>
              Keep in pipeline
            </Button>
            <Button variant="danger" loading={withdrawing} onClick={withdraw}>
              Withdraw candidate
            </Button>
          </>
        }
      >
        <p className="text-sm text-slate-700">
          This cancels every round that hasn&apos;t finished and sets <strong>{c.name}</strong> to
          withdrawn. There&apos;s no undo endpoint — restarting means creating a new process.
        </p>
      </Modal>
    </>
  );
}

function StartProcessModal({ open, candidateId, onClose, onCreated }) {
  const toast = useToast();
  const jobs = useFetch(() => jobsApi.list(), [], { skip: !open });
  const [jobId, setJobId] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await processesApi.create({ candidateId, jobId });
      onCreated();
    } catch (err) {
      const parsed = parseApiError(err);
      toast.error(
        parsed.status === 409
          ? 'This candidate already has an active process. Finish or cancel it before starting another.'
          : parsed.message,
      );
    } finally {
      setSubmitting(false);
    }
  };

  const openJobs = (jobs.data || []).filter((j) => j.status !== 'CLOSED');

  return (
    <Modal
      open={open}
      title="Start an interview process"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button form="start-process" type="submit" loading={submitting} disabled={!jobId}>
            Create process
          </Button>
        </>
      }
    >
      <form id="start-process" onSubmit={submit} className="space-y-4">
        <InfoNote tone="info">
          Creating a process adds all four rounds at once — screening, technical, managerial and
          HR, in that dependency order — and moves the candidate to <strong>Screening</strong>.
        </InfoNote>
        <Field label="Job" required>
          {jobs.loading ? (
            <LoadingState label="Loading jobs…" />
          ) : (
            <Select value={jobId} onChange={(e) => setJobId(e.target.value)} required>
              <option value="">Select a job…</option>
              {openJobs.map((j) => (
                <option key={j.id} value={j.id}>
                  {j.title} · {j.department}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </form>
    </Modal>
  );
}

function AddSkillModal({ open, candidateId, onClose, onAdded }) {
  const toast = useToast();
  const [form, setForm] = useState({ skillId: '', proficiency: 3, yearsExperience: 1 });
  const [submitting, setSubmitting] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await candidatesApi.addSkill(candidateId, {
        skillId: form.skillId.trim(),
        proficiency: Number(form.proficiency),
        yearsExperience: Number(form.yearsExperience),
      });
      setForm({ skillId: '', proficiency: 3, yearsExperience: 1 });
      onAdded();
    } catch (err) {
      const parsed = parseApiError(err);
      toast.error(
        parsed.status === 409 ? 'This candidate already has that skill.' : parsed.message,
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Add a skill"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button form="add-cand-skill" type="submit" loading={submitting}>
            Add skill
          </Button>
        </>
      }
    >
      <form id="add-cand-skill" onSubmit={submit} className="space-y-4">
        {/* There is no skill-creation endpoint — skills are seeded on the backend. */}
        <InfoNote tone="gap">
          Skills come from the backend&apos;s seeded <code>skills</code> table and there&apos;s no
          API to list or create them, so the skill ID has to be pasted in. Adding a brand-new
          skill is a backend seed change, not a form.
        </InfoNote>
        <Field label="Skill ID" required>
          <Input
            required
            value={form.skillId}
            onChange={(e) => setForm({ ...form, skillId: e.target.value })}
            placeholder="00000000-0000-0000-0000-000000000000"
          />
        </Field>
        <div className="grid grid-cols-2 gap-4">
          <Field label="Proficiency" required hint="1–5">
            <Input
              type="number"
              min={1}
              max={5}
              required
              value={form.proficiency}
              onChange={(e) => setForm({ ...form, proficiency: e.target.value })}
            />
          </Field>
          <Field label="Years of experience" required>
            <Input
              type="number"
              min={0}
              required
              value={form.yearsExperience}
              onChange={(e) => setForm({ ...form, yearsExperience: e.target.value })}
            />
          </Field>
        </div>
      </form>
    </Modal>
  );
}
