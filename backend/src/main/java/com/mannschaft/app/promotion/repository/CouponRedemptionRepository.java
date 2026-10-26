package com.mannschaft.app.promotion.repository;

import com.mannschaft.app.promotion.entity.CouponRedemptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * クーポン利用リポジトリ。
 */
public interface CouponRedemptionRepository extends JpaRepository<CouponRedemptionEntity, Long> {

    @Query("SELECT COUNT(cr) FROM CouponRedemptionEntity cr WHERE cr.distributionId = :distributionId")
    long countByDistributionId(@Param("distributionId") Long distributionId);
}
