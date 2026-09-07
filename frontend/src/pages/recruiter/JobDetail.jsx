import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { jobsApi } from '../../api/endpoints';
import { useFetch } from '../../hooks/useAsync';
import { useToast } from '../../context/ToastContext';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../layouts/AppLayout';
import {
  Badge, Button, Card, CardBody, CardHeader, DescriptionList, EmptyState,
  ErrorState, Field, InfoNote, Input, LoadingState, Modal, Select,
} from '../../components/ui';
import { JobStatusBadge } from '../../components/StatusBadge';
import { JOB_STATUSES } from '../../constants/enums';
import { formatDate } from '../../utils/datetime';
import { humanize } from '../../utils/format';
import { parseApiError } from '../../utils/errors';

export default function JobDetail() {
  const { id } = useParams();
  const toast = useToast();
  const { role } = useAuth();
  const isStaff = role === 'RECRUITER' || role === 'ADMIN';

  const job = useFetch(() => jobsApi.get(id), [id]);
  const skills = useFetch(() => jobsApi.skills(id), [id]);
  const [addingSkill, setAddingSkill] = useState(false);
  const [editing, setEditing] = useState(false);
  const [closeOpen, setCloseOpen] = useState(false);
  const [closing, setClosing] = useState(false);

  if (job.loading) return <LoadingState />;
  if (job.error) return <ErrorState error={parseApiError(job.error)} onRetry={job.reload} />;

  const j = job.data;

  const closeJob = async () => {
    setClosing(true);
    try {
      await jobsApi.close(id);
      toast.success('Job closed.');
      setCloseOpen(false);
      job.reload();
    } catch (err) {
      toast.apiError(err);
    } finally {
      setClosing(false);
    }
  };

  return (
    <>
      <PageHeader
        breadcrumb={
          <Link to="/recruiter/jobs" className="hover:underline">
            ← Jobs
          </Link>
        }
        title={j.title}
        subtitle={`${j.department || '—'} · ${j.domain || '—'}`}
        actions={
          isStaff && (
            <>
              <Button variant="secondary" onClick={() => setEditing(true)}>
                Edit
              </Button>
              {j.status !== 'CLOSED' && (
                <Button variant="danger" onClick={() => setCloseOpen(true)}>
                  Close job
                </Button>
              )}
            </>
          )
        }
      />

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          <Card>
            <CardHeader title="Description" action={<JobStatusBadge status={j.status} />} />
            <CardBody>
              <p className="whitespace-pre-wrap text-sm text-slate-700">
                {j.description || 'No description.'}
              </p>
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Required skills"
              subtitle="Interviewers must hold every required skill at its minimum proficiency."
              action={
                isStaff && (
                  <Button variant="secondary" size="sm" onClick={() => setAddingSkill(true)}>
                    Add skill
                  </Button>
                )
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
                      className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-slate-200 px-3 py-2"
                    >
                      <span className="text-sm font-medium text-slate-800">{s.skillName}</span>
                      <span className="flex items-center gap-2">
                        <Badge tone={s.required ? 'red' : 'slate'}>
                          {s.required ? 'Required' : 'Optional'}
                        </Badge>
                        <Badge tone="blue">min level {s.minimumProficiency}</Badge>
                        <span className="text-xs text-slate-500">weight {s.weight}</span>
                      </span>
                    </li>
                  ))}
                </ul>
              ) : (
                <EmptyState
                  icon="🧩"
                  title="No skills on this job"
                  detail="Without required skills, interviewer matching has nothing to match on."
                />
              ))}
            </CardBody>
          </Card>
        </div>

        <div className="space-y-5">
          <Card>
            <CardHeader title="Details" />
            <CardBody>
              <DescriptionList
                className="sm:grid-cols-1"
                items={[
                  { label: 'Status', value: <JobStatusBadge status={j.status} /> },
                  { label: 'Department', value: j.department || '—' },
                  { label: 'Domain', value: j.domain || '—' },
                  { label: 'Created', value: formatDate(j.createdAt) },
                  { label: 'Job ID', value: <code className="text-xs">{j.id}</code> },
                ]}
              />
            </CardBody>
          </Card>

          {j.status === 'DRAFT' && (
            <InfoNote tone="info" title="This job is still a draft">
              Drafts can still have processes created against them. Edit the job to set its status
              to <strong>Open</strong> when it&apos;s ready.
            </InfoNote>
          )}
        </div>
      </div>

      <AddJobSkillModal
        open={addingSkill}
        jobId={id}
        onClose={() => setAddingSkill(false)}
        onAdded={() => {
          setAddingSkill(false);
          toast.success('Skill added to the job.');
          skills.reload();
        }}
      />

      <EditJobModal
        open={editing}
        job={j}
        onClose={() => setEditing(false)}
        onSaved={() => {
          setEditing(false);
          toast.success('Job updated.');
          job.reload();
        }}
      />

      <Modal
        open={closeOpen}
        title="Close this job?"
        onClose={() => setCloseOpen(false)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setCloseOpen(false)}>
              Keep open
            </Button>
            <Button variant="danger" loading={closing} onClick={closeJob}>
              Close job
            </Button>
          </>
        }
      >
        <p className="text-sm text-slate-700">
          Closing marks <strong>{j.title}</strong> as closed. Existing interview processes
          aren&apos;t cancelled by this — close them from each candidate if that&apos;s what you
          want.
        </p>
      </Modal>
    </>
  );
}

function AddJobSkillModal({ open, jobId, onClose, onAdded }) {
  const toast = useToast();
  const [form, setForm] = useState({
    skillId: '', required: 'true', weight: 1, minimumProficiency: 3,
  });
  const [submitting, setSubmitting] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await jobsApi.addSkill(jobId, {
        skillId: form.skillId.trim(),
        required: form.required === 'true',
        weight: Number(form.weight),
        minimumProficiency: Number(form.minimumProficiency),
      });
      setForm({ skillId: '', required: 'true', weight: 1, minimumProficiency: 3 });
      onAdded();
    } catch (err) {
      toast.error(parseApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Add a required skill"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button form="add-job-skill" type="submit" loading={submitting}>
            Add skill
          </Button>
        </>
      }
    >
      <form id="add-job-skill" onSubmit={submit} className="space-y-4">
        <InfoNote tone="gap">
          The skill has to already exist in the backend&apos;s seeded <code>skills</code> table —
          there&apos;s no endpoint to list or create one, so paste its ID here.
        </InfoNote>
        <Field label="Skill ID" required>
          <Input required value={form.skillId} onChange={set('skillId')} placeholder="UUID" />
        </Field>
        <div className="grid grid-cols-3 gap-4">
          <Field label="Required" hint="Disqualifying">
            <Select value={form.required} onChange={set('required')}>
              <option value="true">Yes</option>
              <option value="false">No</option>
            </Select>
          </Field>
          <Field label="Min proficiency" hint="1–5">
            <Input
              type="number"
              min={1}
              max={5}
              value={form.minimumProficiency}
              onChange={set('minimumProficiency')}
            />
          </Field>
          <Field label="Weight" hint="Ranking only">
            <Input type="number" min={0} step="0.5" value={form.weight} onChange={set('weight')} />
          </Field>
        </div>
      </form>
    </Modal>
  );
}

function EditJobModal({ open, job, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({
    title: job.title || '',
    description: job.description || '',
    department: job.department || '',
    domain: job.domain || '',
    status: job.status || 'DRAFT',
  });
  const [submitting, setSubmitting] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await jobsApi.update(job.id, form);
      onSaved();
    } catch (err) {
      toast.error(parseApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Edit job"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button form="edit-job" type="submit" loading={submitting}>
            Save changes
          </Button>
        </>
      }
    >
      <form id="edit-job" onSubmit={submit} className="space-y-4">
        <Field label="Title" required>
          <Input required value={form.title} onChange={set('title')} />
        </Field>
        <Field label="Description">
          <Input value={form.description} onChange={set('description')} />
        </Field>
        <div className="grid grid-cols-2 gap-4">
          <Field label="Department" required>
            <Input required value={form.department} onChange={set('department')} />
          </Field>
          <Field label="Domain" required>
            <Input required value={form.domain} onChange={set('domain')} />
          </Field>
        </div>
        <Field label="Status">
          <Select value={form.status} onChange={set('status')}>
            {JOB_STATUSES.map((s) => (
              <option key={s} value={s}>
                {humanize(s)}
              </option>
            ))}
          </Select>
        </Field>
      </form>
    </Modal>
  );
}
