package com.interviewscheduler.audit;

import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /** Phase 24 — GET /api/admin/audit-logs. Every filter is optional; results are newest first. */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> search(UUID actorId, AuditAction action, String entityType, UUID entityId,
                                          OffsetDateTime dateFrom, OffsetDateTime dateTo, int limit) {
        Specification<AuditLog> spec = Specification.where(null);
        if (actorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("actor").get("id"), actorId));
        }
        if (action != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("action"), action));
        }
        if (entityType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("entityType"), entityType));
        }
        if (entityId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("entityId"), entityId));
        }
        if (dateFrom != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
        }
        if (dateTo != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), dateTo));
        }
        return auditLogRepository.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(AuditLogResponse::from)
                .toList();
    }
}
