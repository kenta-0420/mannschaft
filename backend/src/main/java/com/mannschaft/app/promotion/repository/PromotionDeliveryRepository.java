package com.mannschaft.app.promotion.repository;

import com.mannschaft.app.promotion.entity.PromotionDeliveryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * プロモーション配信リポジトリ。
 */
public interface PromotionDeliveryRepository extends JpaRepository<PromotionDeliveryEntity, Long> {

    Page<PromotionDeliveryEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<PromotionDeliveryEntity> findByIdAndUserId(Long id, Long userId);

    @Query("SELECT COUNT(d) FROM PromotionDeliveryEntity d WHERE d.promotionId = :promotionId AND d.status = :status")
    long countByPromotionIdAndStatus(@Param("promotionId") Long promotionId, @Param("status") String status);
}
