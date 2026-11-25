package com.distribuidora.identity.infrastructure;

import com.distribuidora.identity.domain.UserActivationToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface UserActivationTokenRepository extends JpaRepository<UserActivationToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserActivationToken> findByTokenHash(String tokenHash);
}
