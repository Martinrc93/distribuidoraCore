package com.distribuidora.identity.infrastructure;

import com.distribuidora.identity.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);

    @Query(value = """
        select distinct p.code
        from identity.user_roles ur
        join identity.role_permissions rp on rp.role_id = ur.role_id
        join identity.permissions p on p.id = rp.permission_id
        where ur.user_id = ?1
        order by p.code
        """, nativeQuery = true)
    List<String> findAuthorityCodes(UUID userId);
}
