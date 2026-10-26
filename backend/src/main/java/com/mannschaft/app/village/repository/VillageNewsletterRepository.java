package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageNewsletterEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村ニュースレター設定リポジトリ（F17.1 Phase 3-β-E）。
 *
 * <p>原則7 適用外（村ドメインは organization_id を持たないため）。</p>
 */
public interface VillageNewsletterRepository extends JpaRepository<VillageNewsletterEntity, UUID> {

    /** 村と頻度で 1 レコードを取得（UNIQUE 制約と整合）。 */
    Optional<VillageNewsletterEntity> findByVillageIdAndFrequencyAndDeletedAtIsNull(
            UUID villageId, VillageNewsletterFrequency frequency);

    /** 村のニュースレター設定一覧（生きているもの）。 */
    List<VillageNewsletterEntity> findByVillageIdAndDeletedAtIsNull(UUID villageId);

    /** 配信バッチ用: 指定頻度・有効状態のニュースレターを一括取得。 */
    List<VillageNewsletterEntity> findByFrequencyAndIsEnabledTrueAndDeletedAtIsNull(
            VillageNewsletterFrequency frequency);
}
