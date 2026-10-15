package com.mannschaft.app.promotion.repository;

import com.mannschaft.app.promotion.entity.PromotionDeliverySummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * プロモーション配信サマリーリポジトリ。
 */
public interface PromotionDeliverySummaryRepository extends JpaRepository<PromotionDeliverySummaryEntity, Long> {

    List<PromotionDeliverySummaryEntity> findByPromotionIdOrderBySummaryDateAsc(Long promotionId);
}
