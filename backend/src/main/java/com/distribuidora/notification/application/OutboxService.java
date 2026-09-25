package com.distribuidora.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OutboxService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String eventType, String aggregateType, UUID aggregateId,
                        Object payload, String idempotencyKey) {
        if (eventType == null || eventType.isBlank() || aggregateType == null || aggregateType.isBlank()
            || aggregateId == null || payload == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Los datos del evento outbox son obligatorios");
        }
        try {
            UUID eventId = UUID.randomUUID();
            jdbc.update("insert into notification.outbox_events "
                    + "(id, event_type, aggregate_type, aggregate_id, payload, idempotency_key) "
                    + "values (?, ?, ?, ?, ?::jsonb, ?) on conflict (idempotency_key) do nothing",
                eventId, eventType, aggregateType, aggregateId, objectMapper.writeValueAsString(payload), idempotencyKey);
            return jdbc.queryForObject("select id from notification.outbox_events where idempotency_key = ?",
                UUID.class, idempotencyKey);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("No se pudo serializar el evento outbox", exception);
        }
    }
}
