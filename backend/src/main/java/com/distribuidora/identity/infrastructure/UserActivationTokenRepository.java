package com.distribuidora.identity.infrastructure;

import com.distribuidora.identity.domain.UserActivationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserActivationTokenRepository extends JpaRepository<UserActivationToken, UUID> {

    Optional<UserActivationToken> findByTokenHash(String tokenHash);
}
