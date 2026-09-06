package com.interviewscheduler.interviewer;

import com.interviewscheduler.common.Skill;
import com.interviewscheduler.common.SkillRepository;
import com.interviewscheduler.common.exception.DuplicateResourceException;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
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
public class InterviewerService {

    private final InterviewerProfileRepository interviewerProfileRepository;
    private final InterviewerSkillRepository interviewerSkillRepository;
    private final SkillRepository skillRepository;
    private final UserRepository userRepository;

    @Transactional
    public InterviewerResponse create(CreateInterviewerProfileRequest request) {
        requireRecruiterOrAdmin("create interviewer profiles");
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("No user with id: " + request.userId()));
        if (user.getRole() != Role.INTERVIEWER) {
            throw new com.interviewscheduler.common.exception.ConflictException(
                    "User must have INTERVIEWER role to create an interviewer profile");
        }
        if (interviewerProfileRepository.findByUserId(request.userId()).isPresent()) {
            throw new DuplicateResourceException("Interviewer profile already exists for user: " + request.userId());
        }

        InterviewerProfile profile = new InterviewerProfile();
        profile.setUser(user);
        profile.setDepartment(request.department());
        profile.setDesignation(request.designation());
        profile.setDomain(request.domain());
        if (request.maxInterviewsPerDay() != null) {
            profile.setMaxInterviewsPerDay(request.maxInterviewsPerDay());
        }

        return InterviewerResponse.from(interviewerProfileRepository.save(profile));
    }

    // readOnly: response mapping reads interviewer.getUser().getName()/getEmail(), lazy relations,
    // same reasoning as CandidateService/JobService.
    @Transactional(readOnly = true)
    public List<InterviewerResponse> findAll() {
        requireRecruiterOrAdmin("list interviewers");
        return interviewerProfileRepository.findAll().stream().map(InterviewerResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public InterviewerResponse findById(UUID id) {
        return InterviewerResponse.from(getOwnedOrStaff(id));
    }

    @Transactional(readOnly = true)
    public List<InterviewerSkillResponse> getSkills(UUID id) {
        getOwnedOrStaff(id); // authorization check; result unused
        return interviewerSkillRepository.findByInterviewerId(id).stream()
                .map(InterviewerSkillResponse::from)
                .toList();
    }

    @Transactional
    public InterviewerSkillResponse addSkill(UUID id, InterviewerSkillRequest request) {
        InterviewerProfile interviewer = getOwnedOrStaff(id);
        if (interviewerSkillRepository.existsByInterviewerIdAndSkillId(id, request.skillId())) {
            throw new DuplicateResourceException("Interviewer already has skill: " + request.skillId());
        }
        Skill skill = skillRepository.findById(request.skillId())
                .orElseThrow(() -> new ResourceNotFoundException("No skill with id: " + request.skillId()));

        InterviewerSkill interviewerSkill = new InterviewerSkill();
        interviewerSkill.setInterviewer(interviewer);
        interviewerSkill.setSkill(skill);
        interviewerSkill.setProficiency(request.proficiency());
        interviewerSkill.setYearsExperience(request.yearsExperience());
        interviewerSkill.setPrimary(request.isPrimary());

        return InterviewerSkillResponse.from(interviewerSkillRepository.save(interviewerSkill));
    }

    /** RECRUITER/ADMIN can access any interviewer; the interviewer can access only themselves. */
    private InterviewerProfile getOwnedOrStaff(UUID interviewerId) {
        InterviewerProfile interviewer = interviewerProfileRepository.findById(interviewerId)
                .orElseThrow(() -> new ResourceNotFoundException("No interviewer with id: " + interviewerId));

        UserPrincipal caller = SecurityUtils.currentUser();
        boolean isStaff = caller.getRole() == Role.RECRUITER || caller.getRole() == Role.ADMIN;
        boolean isSelf = interviewer.getUser().getId().equals(caller.getId());
        if (!isStaff && !isSelf) {
            throw new ForbiddenException("Cannot access another interviewer's record");
        }
        return interviewer;
    }

    private void requireRecruiterOrAdmin(String action) {
        UserPrincipal caller = SecurityUtils.currentUser();
        if (caller.getRole() != Role.RECRUITER && caller.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Only recruiters or admins can " + action);
        }
    }
}
