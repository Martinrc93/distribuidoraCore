package com.distribuidora.identity.infrastructure;

import com.distribuidora.identity.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

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

    @Query(value = "select id from identity.roles where code = ?1", nativeQuery = true)
    UUID findRoleId(String code);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "insert into identity.user_roles(user_id, role_id) values (?1, ?2) on conflict do nothing", nativeQuery = true)
    void assignRole(UUID userId, UUID roleId);

    @Transactional
    @Modifying
    @Query("update UserAccount u set u.version = u.version + 1, u.updatedAt = ?2 where u.id = ?1")
    void incrementSessionVersion(UUID userId, java.time.Instant updatedAt);
}
