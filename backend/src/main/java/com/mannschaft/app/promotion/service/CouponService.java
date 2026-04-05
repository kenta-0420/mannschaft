package com.mannschaft.app.promotion.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.promotion.PromotionErrorCode;
import com.mannschaft.app.promotion.dto.CouponResponse;
import com.mannschaft.app.promotion.dto.CreateCouponRequest;
import com.mannschaft.app.promotion.dto.RedeemCouponRequest;
import com.mannschaft.app.promotion.dto.UserCouponResponse;
import com.mannschaft.app.promotion.entity.CouponDistributionEntity;
import com.mannschaft.app.promotion.entity.CouponEntity;
import com.mannschaft.app.promotion.entity.CouponRedemptionEntity;
import com.mannschaft.app.promotion.mapper.PromotionMapper;
import com.mannschaft.app.promotion.repository.CouponDistributionRepository;
import com.mannschaft.app.promotion.repository.CouponRedemptionRepository;
import com.mannschaft.app.promotion.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * クーポン管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponDistributionRepository distributionRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final PromotionMapper promotionMapper;

    /**
     * クーポン一覧を取得する。
     */
    public Page<CouponResponse> list(String scopeType, Long scopeId, Pageable pageable) {
        return couponRepository.findByScopeTypeAndScopeId(scopeType, scopeId, pageable)
                .map(promotionMapper::toCouponResponse);
    }

    /**
     * クーポンを作成する。
     */
    @Transactional
    public CouponResponse create(String scopeType, Long scopeId, Long userId, CreateCouponRequest request) {
        CouponEntity entity = CouponEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .createdBy(userId)
                .title(request.getTitle())
                .description(request.getDescription())
                .couponType(request.getCouponType())
                .discountValue(request.getDiscountValue())
                .minPurchaseAmount(request.getMinPurchaseAmount())
                .maxIssues(request.getMaxIssues())
                .maxUsesPerUser(request.getMaxUsesPerUser() != null ? request.getMaxUsesPerUser() : 1)
                .validFrom(request.getValidFrom())
                .validUntil(request.getValidUntil())
                .build();
        CouponEntity saved = couponRepository.save(entity);
        log.info("クーポン作成: scopeType={}, scopeId={}, id={}", scopeType, scopeId, saved.getId());
        return promotionMapper.toCouponResponse(saved);
    }

    /**
     * クーポン詳細を取得する。
     */
    public CouponResponse get(String scopeType, Long scopeId, Long id) {
        CouponEntity entity = findOrThrow(scopeType, scopeId, id);
        return promotionMapper.toCouponResponse(entity);
    }

    /**
     * クーポンを更新する。
     */
    @Transactional
    public CouponResponse update(String scopeType, Long scopeId, Long id, CreateCouponRequest request) {
        CouponEntity entity = findOrThrow(scopeType, scopeId, id);
        entity.update(request.getTitle(), request.getDescription(), request.getCouponType(),
                request.getDiscountValue(), request.getMinPurchaseAmount(),
                request.getMaxIssues(),
                request.getMaxUsesPerUser() != null ? request.getMaxUsesPerUser() : 1,
                request.getValidFrom(), request.getValidUntil());
        CouponEntity saved = couponRepository.save(entity);
        log.info("クーポン更新: id={}", id);
        return promotionMapper.toCouponResponse(saved);
    }

    /**
     * クーポンを削除する。
     */
    @Transactional
    public void delete(String scopeType, Long scopeId, Long id) {
        CouponEntity entity = findOrThrow(scopeType, scopeId, id);
        entity.softDelete();
        couponRepository.save(entity);
        log.info("クーポン削除: id={}", id);
    }

    /**
     * クーポンの有効/無効を切り替える。
     */
    @Transactional
    public CouponResponse toggle(String scopeType, Long scopeId, Long id) {
        CouponEntity entity = findOrThrow(scopeType, scopeId, id);
        entity.toggleActive();
        CouponEntity saved = couponRepository.save(entity);
        log.info("クーポン切替: id={}, isActive={}", id, saved.getIsActive());
        return promotionMapper.toCouponResponse(saved);
    }

    /**
     * ユーザーの保有クーポン一覧を取得する。
     */
    public List<UserCouponResponse> listUserCoupons(Long userId) {
        List<CouponDistributionEntity> distributions = distributionRepository.findByUserId(userId);
        return distributions.stream().map(dist -> {
            CouponEntity coupon = couponRepository.findById(dist.getCouponId()).orElse(null);
            return new UserCouponResponse(
                    dist.getId(), dist.getCouponId(),
                    coupon != null ? coupon.getTitle() : null,
                    coupon != null ? coupon.getDescription() : null,
                    coupon != null ? coupon.getCouponType() : null,
                    coupon != null ? coupon.getDiscountValue() : null,
                    dist.getStatus(), dist.getDistributedAt(), dist.getExpiresAt());
        }).collect(Collectors.toList());
    }

    /**
     * クーポンを利用する。
     */
    @Transactional
    public void redeem(Long userId, Long distributionId, RedeemCouponRequest request) {
        CouponDistributionEntity distribution = distributionRepository.findByIdAndUserId(distributionId, userId)
                .orElseThrow(() -> new BusinessException(PromotionErrorCode.DISTRIBUTION_NOT_FOUND));

        if (!distribution.isRedeemable()) {
            throw new BusinessException(PromotionErrorCode.COUPON_NOT_REDEEMABLE);
        }

        CouponEntity coupon = couponRepository.findById(distribution.getCouponId())
                .orElseThrow(() -> new BusinessException(PromotionErrorCode.COUPON_NOT_FOUND));

        long useCount = redemptionRepository.countByDistributionId(distributionId);
        if (useCount >= coupon.getMaxUsesPerUser()) {
            throw new BusinessException(PromotionErrorCode.COUPON_USE_LIMIT_EXCEEDED);
        }

        CouponRedemptionEntity redemption = CouponRedemptionEntity.builder()
                .distributionId(distributionId)
                .redeemedBy(userId)
                .redeemedAt(LocalDateTime.now())
                .redemptionDetail(request.getRedemptionDetail())
                .build();
        redemptionRepository.save(redemption);

        if (useCount + 1 >= coupon.getMaxUsesPerUser()) {
            distribution.markUsed();
            distributionRepository.save(distribution);
        }

        log.info("クーポン利用: userId={}, distributionId={}", userId, distributionId);
    }

    private CouponEntity findOrThrow(String scopeType, Long scopeId, Long id) {
        return couponRepository.findByIdAndScope(id, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(PromotionErrorCode.COUPON_NOT_FOUND));
    }
}
