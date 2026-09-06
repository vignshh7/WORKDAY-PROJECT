package com.interviewscheduler.audit;

import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

// Log-writing only. The query/filter API (GET /api/admin/audit-logs) is Phase 24 — this
// exists now because Phase 6 requires every mutating action to leave an audit trail, and
// that trail has to exist before there's anything to query.
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Transactional
    public void log(UUID actorId, ActorType actorType, AuditAction action,
                     String entityType, UUID entityId, Map<String, Object> metadata) {
        AuditLog log = new AuditLog();
        log.setActor(actorId == null ? null : userRepository.getReferenceById(actorId));
        log.setActorType(actorType);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setMetadata(metadata);
        auditLogRepository.save(log);
    }

    /** For actions taken by the currently authenticated caller (the common case). */
    public void logForCurrentUser(AuditAction action, String entityType, UUID entityId, Map<String, Object> metadata) {
        UserPrincipal principal = SecurityUtils.currentUser();
        log(principal.getId(), ActorType.USER, action, entityType, entityId, metadata);
    }
}
