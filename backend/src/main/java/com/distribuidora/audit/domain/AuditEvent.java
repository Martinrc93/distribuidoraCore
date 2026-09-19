package com.distribuidora.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(schema = "audit", name = "audit_events")
public class AuditEvent {
    @Id
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(nullable = false, length = 120)
    private String operation;

    @Column(name = "resource_type", nullable = false, length = 120)
    private String resourceType;

    @Column(name = "resource_id", length = 120)
    private String resourceId;

    @Column(nullable = false, length = 30)
    private String result;

    @Column(name = "correlation_id", nullable = false, length = 120)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(nullable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(UUID actorUserId, String operation, String resourceType, String resourceId,
                      String result, String correlationId, Map<String, Object> details) {
        this.actorUserId = actorUserId;
        this.operation = operation;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.result = result;
        this.correlationId = correlationId;
        this.details = details == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(details));
    }

    @PrePersist
    void onCreate() {
        id = id == null ? UUID.randomUUID() : id;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
