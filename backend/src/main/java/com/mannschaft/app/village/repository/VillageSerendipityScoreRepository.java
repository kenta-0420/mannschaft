package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageSerendipityScoreEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — ご縁スコアリポジトリ。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断）。標準 {@link JpaRepository} を継承。</p>
 */
public interface VillageSerendipityScoreRepository
        extends JpaRepository<VillageSerendipityScoreEntity, UUID> {

    /** 指定村・指定ユーザーのスコアを取得する。 */
    Optional<VillageSerendipityScoreEntity> findByVillageIdAndUserId(UUID villageId, Long userId);

    /** 指定村のスコアを interactionScore 降順で取得する（ランキング用）。 */
    Page<VillageSerendipityScoreEntity> findByVillageIdOrderByInteractionScoreDesc(
            UUID villageId, Pageable pageable);

    /** 指定村の総レコード数。 */
    long countByVillageId(UUID villageId);
}
