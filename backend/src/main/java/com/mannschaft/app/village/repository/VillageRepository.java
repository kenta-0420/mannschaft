package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * 論理削除されていない村をページネーション取得する（バッチチャンク処理用）。
     *
     * <p>findAll() 無制限取得の代替。CHUNK_SIZE=500 で呼び出すことで
     * 大量データでもヒープを圧迫しない。</p>
     */
    Page<VillageEntity> findByDeletedAtIsNull(Pageable pageable);

    /**
     * 論理削除・凍結どちらもされていない村をページネーション取得する（バッチチャンク処理用）。
     *
     * <p>findAll() 無制限取得の代替。CHUNK_SIZE=500 で呼び出すことで
     * 大量データでもヒープを圧迫しない。</p>
     */
    Page<VillageEntity> findByDeletedAtIsNullAndArchivedAtIsNull(Pageable pageable);
}
