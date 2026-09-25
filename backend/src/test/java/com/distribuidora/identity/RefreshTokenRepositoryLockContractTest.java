package com.distribuidora.identity;

import com.distribuidora.identity.infrastructure.RefreshTokenRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenRepositoryLockContractTest {

    @Test
    void tokenLookupUsesPessimisticWriteLockForRotation() throws Exception {
        Lock lock = RefreshTokenRepository.class.getMethod("findByTokenHash", String.class)
            .getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
