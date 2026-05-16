package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageNewsletterSendLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 村ニュースレター配信履歴リポジトリ（F17.1 Phase 3-β-E）。
 */
public interface VillageNewsletterSendLogRepository
        extends JpaRepository<VillageNewsletterSendLogEntity, UUID> {

    /** newsletter 単位の配信履歴（新しい順）。 */
    List<VillageNewsletterSendLogEntity> findByNewsletterIdOrderBySentAtDesc(UUID newsletterId);
}
