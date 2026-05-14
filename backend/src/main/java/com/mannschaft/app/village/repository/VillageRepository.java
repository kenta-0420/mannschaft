package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 村本体リポジトリ（F17.1 Phase 1）。
 *
 * <p>原則7 適用外: 全テナント横断ゆえ {@code AbstractTenantAwareRepository} を継承しない。</p>
 */
public interface VillageRepository extends JpaRepository<VillageEntity, UUID> {

    Optional<VillageEntity> findBySlugAndDeletedAtIsNullAndArchivedAtIsNull(String slug);

    Optional<VillageEntity> findByIdAndDeletedAtIsNullAndArchivedAtIsNull(UUID id);

    boolean existsBySlug(String slug);

    boolean existsByName(String name);
}
