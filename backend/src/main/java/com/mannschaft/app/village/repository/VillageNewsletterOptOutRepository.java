package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageNewsletterOptOutEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村ニュースレター opt-out リポジトリ（F17.1 Phase 3-β-E）。
 */
public interface VillageNewsletterOptOutRepository
        extends JpaRepository<VillageNewsletterOptOutEntity, UUID> {

    /** 村×ユーザーで opt-out 状況を確認。 */
    Optional<VillageNewsletterOptOutEntity> findByVillageIdAndUserId(UUID villageId, Long userId);

    /** 配信バッチ用: 村単位の opt-out user_id 一覧。 */
    List<VillageNewsletterOptOutEntity> findByVillageId(UUID villageId);

    /** opt-out 存在判定。 */
    boolean existsByVillageIdAndUserId(UUID villageId, Long userId);
}
