package com.mannschaft.app.promotion.repository;

import com.mannschaft.app.promotion.entity.PromotionSegmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * プロモーションセグメントリポジトリ。
 */
public interface PromotionSegmentRepository extends JpaRepository<PromotionSegmentEntity, Long> {

    List<PromotionSegmentEntity> findByPromotionId(Long promotionId);

    void deleteByPromotionId(Long promotionId);
}
