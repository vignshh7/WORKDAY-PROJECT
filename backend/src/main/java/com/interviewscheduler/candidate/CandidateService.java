package com.interviewscheduler.candidate;

import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.common.Skill;
import com.interviewscheduler.common.SkillRepository;
import com.interviewscheduler.common.exception.DuplicateResourceException;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.interview.InterviewProcess;
import com.interviewscheduler.interview.InterviewProcessRepository;
import com.interviewscheduler.interview.InterviewProcessResponse;
import com.interviewscheduler.interview.InterviewRoundRepository;
import com.interviewscheduler.interview.InterviewRoundResponse;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.Role;
import com.interviewscheduler.user.User;
import com.interviewscheduler.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CandidateService {

    private final CandidateRepository candidateRepository;
    private final CandidateSkillRepository candidateSkillRepository;
    private final UserRepository userRepository;
    private final SkillRepository skillRepository;
    private final InterviewProcessRepository interviewProcessRepository;
    private final InterviewRoundRepository interviewRoundRepository;
    private final AuditService auditService;

    @Transactional
    public CandidateResponse create(CreateCandidateRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("No user with id: " + request.userId()));
        if (user.getRole() != Role.CANDIDATE) {
            throw new ForbiddenException("User " + user.getId() + " does not have the CANDIDATE role");
        }
        if (candidateRepository.findByUserId(user.getId()).isPresent()) {
            throw new DuplicateResourceException("Candidate profile already exists for user: " + user.getId());
        }

        Candidate candidate = new Candidate();
        candidate.setUser(user);
        candidate.setPhone(request.phone());
        candidate.setResumeUrl(request.resumeUrl());
        Candidate saved = candidateRepository.saveAndFlush(candidate);

        auditService.logForCurrentUser(AuditAction.CANDIDATE_CREATED, "CANDIDATE", saved.getId(), null);

        return CandidateResponse.from(saved);
    }

    // readOnly transactions here (not just plain reads) because the response mapping below
    // touches lazy relations (candidate.getUser().getName(), skill.getSkill().getName(), ...)
    // — those need an open Hibernate session, which a plain repository call closes as soon
    // as it returns.
    @Transactional(readOnly = true)
    public List<CandidateResponse> findAll() {
        requireRecruiterOrAdmin("list candidates");
        return candidateRepository.findAll().stream().map(CandidateResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CandidateResponse findById(UUID id) {
        return CandidateResponse.from(getOwnedOrStaff(id));
    }

    /**
     * Resolves the signed-in CANDIDATE's own profile. Exists because {@link #findAll} is
     * staff-only, so a candidate has no other way to discover their own candidate id (which
     * every other candidate-scoped endpoint requires) — see the frontend's previous manual
     * "paste your candidate id" workaround, which this replaces.
     */
    @Transactional(readOnly = true)
    public CandidateResponse findMine() {
        UserPrincipal caller = SecurityUtils.currentUser();
        Candidate candidate = candidateRepository.findByUserId(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No candidate profile for the current user"));
        return CandidateResponse.from(candidate);
    }

    @Transactional
    public CandidateResponse update(UUID id, UpdateCandidateRequest request) {
        Candidate candidate = getOwnedOrStaff(id);
        candidate.setPhone(request.phone());
        candidate.setResumeUrl(request.resumeUrl());
        return CandidateResponse.from(candidateRepository.saveAndFlush(candidate));
    }

    @Transactional(readOnly = true)
    public List<CandidateSkillResponse> getSkills(UUID id) {
        getOwnedOrStaff(id); // authorization check; result unused
        return candidateSkillRepository.findByCandidateId(id).stream()
                .map(CandidateSkillResponse::from)
                .toList();
    }

    @Transactional
    public CandidateSkillResponse addSkill(UUID id, CandidateSkillRequest request) {
        Candidate candidate = getOwnedOrStaff(id);
        if (candidateSkillRepository.existsByCandidateIdAndSkillId(id, request.skillId())) {
            throw new DuplicateResourceException("Candidate already has skill: " + request.skillId());
        }
        Skill skill = skillRepository.findById(request.skillId())
                .orElseThrow(() -> new ResourceNotFoundException("No skill with id: " + request.skillId()));

        CandidateSkill candidateSkill = new CandidateSkill();
        candidateSkill.setCandidate(candidate);
        candidateSkill.setSkill(skill);
        candidateSkill.setProficiency(request.proficiency());
        candidateSkill.setYearsExperience(request.yearsExperience());

        return CandidateSkillResponse.from(candidateSkillRepository.save(candidateSkill));
    }

    @Transactional(readOnly = true)
    public CandidateStatusResponse getStatus(UUID id) {
        return new CandidateStatusResponse(getOwnedOrStaff(id).getCurrentStatus());
    }

    @Transactional(readOnly = true)
    public List<CandidatePipelineEntry> getPipeline(UUID id) {
        getOwnedOrStaff(id);
        List<InterviewProcess> processes = interviewProcessRepository.findByCandidateId(id);
        return processes.stream()
                .map(process -> new CandidatePipelineEntry(
                        InterviewProcessResponse.from(process),
                        interviewRoundRepository.findByProcessIdOrderByRoundNumberAsc(process.getId()).stream()
                                .map(InterviewRoundResponse::from)
                                .toList()))
                .toList();
    }

    /** RECRUITER/ADMIN can access any candidate; the candidate can access only themselves. */
    private Candidate getOwnedOrStaff(UUID candidateId) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("No candidate with id: " + candidateId));

        UserPrincipal caller = SecurityUtils.currentUser();
        boolean isStaff = caller.getRole() == Role.RECRUITER || caller.getRole() == Role.ADMIN;
        boolean isSelf = candidate.getUser().getId().equals(caller.getId());
        if (!isStaff && !isSelf) {
            throw new ForbiddenException("Cannot access another candidate's record");
        }
        return candidate;
    }

    private void requireRecruiterOrAdmin(String action) {
        UserPrincipal caller = SecurityUtils.currentUser();
        if (caller.getRole() != Role.RECRUITER && caller.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Only recruiters or admins can " + action);
        }
    }
}
