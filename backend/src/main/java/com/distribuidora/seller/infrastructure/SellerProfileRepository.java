package com.distribuidora.seller.infrastructure;

import com.distribuidora.seller.domain.SellerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SellerProfileRepository extends JpaRepository<SellerProfile, UUID> {
    Optional<SellerProfile> findByUserId(UUID userId);
    boolean existsByUserId(UUID userId);
    boolean existsByUserIdAndIdNot(UUID userId, UUID id);
}
