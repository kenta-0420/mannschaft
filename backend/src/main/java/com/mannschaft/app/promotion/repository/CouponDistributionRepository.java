package com.mannschaft.app.promotion.repository;

import com.mannschaft.app.promotion.entity.CouponDistributionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * クーポン配布リポジトリ。
 */
public interface CouponDistributionRepository extends JpaRepository<CouponDistributionEntity, Long> {

    List<CouponDistributionEntity> findByUserIdAndStatusOrderByExpiresAtAsc(Long userId, String status);

    @Query("SELECT cd FROM CouponDistributionEntity cd WHERE cd.userId = :userId ORDER BY cd.createdAt DESC")
    List<CouponDistributionEntity> findByUserId(@Param("userId") Long userId);

    Optional<CouponDistributionEntity> findByIdAndUserId(Long id, Long userId);

    @Query("SELECT COUNT(cd) FROM CouponDistributionEntity cd WHERE cd.couponId = :couponId AND cd.userId = :userId")
    long countByCouponIdAndUserId(@Param("couponId") Long couponId, @Param("userId") Long userId);
}
