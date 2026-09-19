package com.distribuidora.audit.application;

import com.distribuidora.audit.domain.AuditEvent;
import com.distribuidora.audit.infrastructure.AuditEventRepository;
import com.distribuidora.shared.web.RequestIdContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditEventRepository events;

    public AuditService(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorUserId, String operation, String resourceType, String resourceId,
                       String result, Map<String, Object> details) {
        events.save(new AuditEvent(actorUserId, operation, resourceType, resourceId, result,
            RequestIdContext.currentOrGenerate(), details));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithinTransaction(UUID actorUserId, String operation, String resourceType, String resourceId,
                                        String result, Map<String, Object> details) {
        events.save(new AuditEvent(actorUserId, operation, resourceType, resourceId, result,
            RequestIdContext.currentOrGenerate(), details));
    }
}
