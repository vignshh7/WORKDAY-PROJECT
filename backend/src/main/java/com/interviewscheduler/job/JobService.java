package com.interviewscheduler.job;

import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.common.Skill;
import com.interviewscheduler.common.SkillRepository;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.DuplicateResourceException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.interview.InterviewProcessRepository;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final JobSkillRepository jobSkillRepository;
    private final SkillRepository skillRepository;
    private final UserRepository userRepository;
    private final InterviewProcessRepository interviewProcessRepository;
    private final AuditService auditService;

    @Transactional
    public JobResponse create(CreateJobRequest request) {
        UserPrincipal caller = SecurityUtils.currentUser();

        Job job = new Job();
        job.setTitle(request.title());
        job.setDescription(request.description());
        job.setDepartment(request.department());
        job.setDomain(request.domain());
        job.setCreatedBy(userRepository.getReferenceById(caller.getId()));

        Job saved = jobRepository.saveAndFlush(job);
        auditService.logForCurrentUser(AuditAction.JOB_CREATED, "JOB", saved.getId(), null);

        return JobResponse.from(saved);
    }

    // readOnly, not plain: response mapping reads job.getCreatedBy() / skill.getSkill(),
    // both lazy — needs an open session through the mapping, same issue as CandidateService.
    @Transactional(readOnly = true)
    public List<JobResponse> findAll() {
        return jobRepository.findAll().stream().map(JobResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public JobResponse findById(UUID id) {
        return JobResponse.from(getOrThrow(id));
    }

    @Transactional
    public JobResponse update(UUID id, UpdateJobRequest request) {
        Job job = getOrThrow(id);
        job.setTitle(request.title());
        job.setDescription(request.description());
        job.setDepartment(request.department());
        job.setDomain(request.domain());
        job.setStatus(request.status());
        return JobResponse.from(jobRepository.saveAndFlush(job));
    }

    /**
     * Phase 18 — explicit job closure. Sets status CLOSED without touching any existing
     * interview data (processes/rounds/calendar events continue unchanged). New processes
     * for a CLOSED job are blocked in {@link com.interviewscheduler.interview.InterviewProcessService}.
     */
    @Transactional
    public JobResponse close(UUID id) {
        Job job = getOrThrow(id);
        if (job.getStatus() == JobStatus.CLOSED) {
            throw new ConflictException("Job is already CLOSED");
        }
        job.setStatus(JobStatus.CLOSED);
        Job saved = jobRepository.saveAndFlush(job);
        auditService.logForCurrentUser(AuditAction.JOB_CLOSED, "JOB", saved.getId(), null);
        return JobResponse.from(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Job job = getOrThrow(id);
        if (!interviewProcessRepository.findByJobId(id).isEmpty()) {
            throw new ConflictException(
                    "Cannot delete a job with existing interview processes; close it instead");
        }
        jobRepository.delete(job);
    }

    @Transactional(readOnly = true)
    public List<JobSkillResponse> getSkills(UUID jobId) {
        getOrThrow(jobId);
        return jobSkillRepository.findByJobId(jobId).stream().map(JobSkillResponse::from).toList();
    }

    @Transactional
    public JobSkillResponse addSkill(UUID jobId, JobSkillRequest request) {
        Job job = getOrThrow(jobId);
        if (jobSkillRepository.existsByJobIdAndSkillId(jobId, request.skillId())) {
            throw new DuplicateResourceException("Job already requires skill: " + request.skillId());
        }
        Skill skill = skillRepository.findById(request.skillId())
                .orElseThrow(() -> new ResourceNotFoundException("No skill with id: " + request.skillId()));

        JobSkill jobSkill = new JobSkill();
        jobSkill.setJob(job);
        jobSkill.setSkill(skill);
        jobSkill.setRequired(request.required());
        if (request.weight() != null) {
            jobSkill.setWeight(request.weight());
        }
        jobSkill.setMinimumProficiency(request.minimumProficiency());

        return JobSkillResponse.from(jobSkillRepository.save(jobSkill));
    }

    private Job getOrThrow(UUID id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No job with id: " + id));
    }
}
