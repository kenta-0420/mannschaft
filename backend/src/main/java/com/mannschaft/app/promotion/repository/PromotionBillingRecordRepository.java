package com.mannschaft.app.promotion.repository;

import com.mannschaft.app.promotion.entity.PromotionBillingRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * プロモーション課金記録リポジトリ。
 */
public interface PromotionBillingRecordRepository extends JpaRepository<PromotionBillingRecordEntity, Long> {

    @Query("""
            SELECT pbr FROM PromotionBillingRecordEntity pbr
            WHERE (:billingStatus IS NULL OR pbr.billingStatus = :billingStatus)
            ORDER BY pbr.createdAt DESC
            """)
    Page<PromotionBillingRecordEntity> findAllWithFilter(
            @Param("billingStatus") String billingStatus,
            Pageable pageable);
}
