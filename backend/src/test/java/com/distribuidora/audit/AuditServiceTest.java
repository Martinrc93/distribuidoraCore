package com.distribuidora.audit;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.audit.domain.AuditEvent;
import com.distribuidora.audit.infrastructure.AuditEventRepository;
import com.distribuidora.shared.web.RequestIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditServiceTest {
    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final AuditService service = new AuditService(repository);

    @AfterEach
    void clearContext() {
        RequestIdContext.clear();
    }

    @Test
    void recordPropagatesActorCorrelationIdAndImmutableDetails() throws Exception {
        UUID actorId = UUID.randomUUID();
        RequestIdContext.set("req-123");

        service.record(actorId, "USER_LOGIN", "USER", actorId.toString(), "FAILURE", Map.of("reason", "bad_credentials"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(readField(saved, "actorUserId")).isEqualTo(actorId);
        assertThat(readField(saved, "operation")).isEqualTo("USER_LOGIN");
        assertThat(readField(saved, "resourceType")).isEqualTo("USER");
        assertThat(readField(saved, "resourceId")).isEqualTo(actorId.toString());
        assertThat(readField(saved, "result")).isEqualTo("FAILURE");
        assertThat(readField(saved, "correlationId")).isEqualTo("req-123");
        assertThat(readField(saved, "details")).isEqualTo(Map.of("reason", "bad_credentials"));
    }

    @Test
    void recordWithinTransactionGeneratesCorrelationIdAndHandlesNullDetails() throws Exception {
        UUID actorId = UUID.randomUUID();

        service.recordWithinTransaction(actorId, "ZONE_CREATE", "ZONE", "zone-1", "SUCCESS", null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat((String) readField(saved, "correlationId")).isNotBlank();
        assertThat(readField(saved, "details")).isEqualTo(Map.of());
    }

    @Test
    void preservesTransactionalPropagationContracts() throws Exception {
        var recordTx = AuditService.class.getMethod("record", UUID.class, String.class, String.class, String.class, String.class, Map.class)
            .getAnnotation(Transactional.class);
        var mandatoryTx = AuditService.class.getMethod("recordWithinTransaction", UUID.class, String.class, String.class, String.class, String.class, Map.class)
            .getAnnotation(Transactional.class);

        assertThat(recordTx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(mandatoryTx.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
