package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.CancellationFeeType;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CancellationFeeEstimateResponse;
import com.mannschaft.app.recruitment.dto.CancellationPolicyResponse;
import com.mannschaft.app.recruitment.dto.CancellationPolicyTierRequest;
import com.mannschaft.app.recruitment.dto.CancellationPolicyTierResponse;
import com.mannschaft.app.recruitment.dto.CreateCancellationPolicyRequest;
import com.mannschaft.app.recruitment.dto.UpdateCancellationPolicyRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyTierEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyTierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F03.11 募集型予約: キャンセルポリシー＋料金計算サービス (Phase 5a)。
 *
 * - §3.10〜§3.11 ポリシー/段階の CRUD
 * - §5.9 キャンセル料計算ロジック (純粋関数)
 * - §9.9 試算 API
 * - §14.11 fee_amount は INSERT 後 UPDATE 禁止 (Entity 側で保証)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentCancellationPolicyService {

    private static final int MAX_TIER_COUNT = 4;

    private final RecruitmentCancellationPolicyRepository policyRepository;
    private final RecruitmentCancellationPolicyTierRepository tierRepository;
    private final AccessControlService accessControlService;
    private final RecruitmentMapper mapper;

    // ===========================================
    // CRUD
    // ===========================================

    public List<CancellationPolicyResponse> listByScope(RecruitmentScopeType scopeType, Long scopeId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
        List<RecruitmentCancellationPolicyEntity> policies =
                policyRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId);
        return policies.stream()
                .map(this::buildPolicyResponse)
                .collect(Collectors.toList());
    }

    public CancellationPolicyResponse getPolicy(Long policyId, Long userId) {
        RecruitmentCancellationPolicyEntity policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, policy.getScopeId(), policy.getScopeType().name());
        return buildPolicyResponse(policy);
    }

    @Transactional
    public CancellationPolicyResponse createPolicy(
            RecruitmentScopeType scopeType, Long scopeId, Long userId,
            CreateCancellationPolicyRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());

        validateTierRequests(request.getTiers(), request.getFreeUntilHoursBefore());

        RecruitmentCancellationPolicyEntity policy = RecruitmentCancellationPolicyEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .policyName(request.getPolicyName())
                .freeUntilHoursBefore(request.getFreeUntilHoursBefore())
                .isTemplatePolicy(Boolean.TRUE.equals(request.getIsTemplatePolicy()))
                .createdBy(userId)
                .build();
        RecruitmentCancellationPolicyEntity savedPolicy = policyRepository.save(policy);

        if (request.getTiers() != null) {
            for (CancellationPolicyTierRequest tierReq : request.getTiers()) {
                tierRepository.save(RecruitmentCancellationPolicyTierEntity.builder()
                        .policyId(savedPolicy.getId())
                        .tierOrder(tierReq.getTierOrder())
                        .appliesAtOrBeforeHours(tierReq.getAppliesAtOrBeforeHours())
                        .feeType(tierReq.getFeeType())
                        .feeValue(tierReq.getFeeValue())
                        .build());
            }
        }

        log.info("F03.11 キャンセルポリシー作成: id={}, scope={}/{}, tiers={}",
                savedPolicy.getId(), scopeType, scopeId,
                request.getTiers() != null ? request.getTiers().size() : 0);
        return buildPolicyResponse(savedPolicy);
    }

    @Transactional
    public CancellationPolicyResponse updatePolicy(Long policyId, Long userId, UpdateCancellationPolicyRequest request) {
        RecruitmentCancellationPolicyEntity policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, policy.getScopeId(), policy.getScopeType().name());

        // テンプレートポリシーのみ編集可
        if (!Boolean.TRUE.equals(policy.getIsTemplatePolicy())) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_CANCELLATION_POLICY);
        }

        if (request.getFreeUntilHoursBefore() != null || request.getPolicyName() != null) {
            policy.updateForTemplate(request.getPolicyName(), request.getFreeUntilHoursBefore());
            policyRepository.save(policy);
        }

        if (request.getTiers() != null) {
            validateTierRequests(request.getTiers(), policy.getFreeUntilHoursBefore());
            tierRepository.deleteByPolicyId(policyId);
            for (CancellationPolicyTierRequest tierReq : request.getTiers()) {
                tierRepository.save(RecruitmentCancellationPolicyTierEntity.builder()
                        .policyId(policyId)
                        .tierOrder(tierReq.getTierOrder())
                        .appliesAtOrBeforeHours(tierReq.getAppliesAtOrBeforeHours())
                        .feeType(tierReq.getFeeType())
                        .feeValue(tierReq.getFeeValue())
                        .build());
            }
        }

        log.info("F03.11 キャンセルポリシー更新: id={}", policyId);
        return buildPolicyResponse(policy);
    }

    @Transactional
    public void archivePolicy(Long policyId, Long userId) {
        RecruitmentCancellationPolicyEntity policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, policy.getScopeId(), policy.getScopeType().name());

        policy.softDelete();
        policyRepository.save(policy);
        log.info("F03.11 キャンセルポリシー削除: id={}", policyId);
    }

    // ===========================================
    // §5.9 キャンセル料計算 (純粋関数)
    // ===========================================

    /**
     * 設計書 §5.9 のキャンセル料計算ロジック。
     * 境界値: hoursBefore >= free_until_hours_before → 無料 (等号含む)
     * tier 選択: applies_at_or_before_hours >= hoursBefore を満たす最大の tier_order
     */
    public CalculatedFee calculateFee(RecruitmentListingEntity listing, LocalDateTime cancelAt) {
        // 決済が無効なら常に無料
        if (!Boolean.TRUE.equals(listing.getPaymentEnabled())) {
            return CalculatedFee.free(null, hoursBefore(listing.getStartAt(), cancelAt));
        }

        // ポリシー未設定なら無料
        if (listing.getCancellationPolicyId() == null) {
            return CalculatedFee.free(null, hoursBefore(listing.getStartAt(), cancelAt));
        }

        RecruitmentCancellationPolicyEntity policy = policyRepository
                .findById(listing.getCancellationPolicyId())
                .orElse(null);
        if (policy == null) {
            return CalculatedFee.free(null, hoursBefore(listing.getStartAt(), cancelAt));
        }

        double hoursBefore = hoursBefore(listing.getStartAt(), cancelAt);

        // 境界値: 等号含む (ユーザー有利)
        if (hoursBefore >= policy.getFreeUntilHoursBefore()) {
            return CalculatedFee.free(policy.getId(), hoursBefore);
        }

        // 該当 tier を選択 (条件を満たす最大の tier_order)
        List<RecruitmentCancellationPolicyTierEntity> tiers =
                tierRepository.findByPolicyIdOrderByTierOrderAsc(policy.getId());
        RecruitmentCancellationPolicyTierEntity matchingTier = null;
        for (RecruitmentCancellationPolicyTierEntity tier : tiers) {
            if (tier.getAppliesAtOrBeforeHours() >= hoursBefore) {
                if (matchingTier == null || tier.getTierOrder() > matchingTier.getTierOrder()) {
                    matchingTier = tier;
                }
            }
        }

        if (matchingTier == null) {
            // tier 設定漏れ (フォールバック: 無料)
            return CalculatedFee.free(policy.getId(), hoursBefore);
        }

        int feeAmount;
        if (matchingTier.getFeeType() == CancellationFeeType.PERCENTAGE) {
            int price = listing.getPrice() != null ? listing.getPrice() : 0;
            feeAmount = (int) Math.ceil((double) price * matchingTier.getFeeValue() / 100.0);
        } else {
            feeAmount = matchingTier.getFeeValue();
        }

        return new CalculatedFee(
                policy.getId(),
                matchingTier.getId(),
                matchingTier.getTierOrder(),
                matchingTier.getFeeType().name(),
                feeAmount,
                false,
                hoursBefore);
    }

    public CancellationFeeEstimateResponse estimateFee(RecruitmentListingEntity listing, LocalDateTime atTimestamp) {
        LocalDateTime calcAt = atTimestamp != null ? atTimestamp : LocalDateTime.now();
        CalculatedFee fee = calculateFee(listing, calcAt);
        return new CancellationFeeEstimateResponse(
                listing.getId(),
                fee.policyId(),
                fee.feeAmount(),
                fee.tierId(),
                fee.tierOrder(),
                fee.feeType(),
                fee.freeUntilApplied(),
                fee.hoursBefore(),
                calcAt);
    }

    // ===========================================
    // 内部ヘルパー
    // ===========================================

    private CancellationPolicyResponse buildPolicyResponse(RecruitmentCancellationPolicyEntity policy) {
        List<CancellationPolicyTierResponse> tiers = mapper.toCancellationPolicyTierResponseList(
                tierRepository.findByPolicyIdOrderByTierOrderAsc(policy.getId()));
        CancellationPolicyResponse base = mapper.toCancellationPolicyResponse(policy);
        return new CancellationPolicyResponse(
                base.getId(), base.getScopeType(), base.getScopeId(), base.getPolicyName(),
                base.getFreeUntilHoursBefore(), base.getIsTemplatePolicy(),
                base.getCreatedBy(), base.getCreatedAt(), base.getUpdatedAt(),
                tiers);
    }

    private void validateTierRequests(List<CancellationPolicyTierRequest> tiers, Integer freeUntilHoursBefore) {
        if (tiers == null || tiers.isEmpty()) {
            return;
        }
        if (tiers.size() > MAX_TIER_COUNT) {
            throw new BusinessException(RecruitmentErrorCode.TIER_LIMIT_EXCEEDED);
        }

        // tier_order 重複チェック
        Set<Integer> orders = new HashSet<>();
        for (CancellationPolicyTierRequest tier : tiers) {
            if (!orders.add(tier.getTierOrder())) {
                throw new BusinessException(RecruitmentErrorCode.INVALID_CANCELLATION_POLICY);
            }
        }

        // applies_at_or_before_hours が free_until 未満であること
        for (CancellationPolicyTierRequest tier : tiers) {
            if (tier.getAppliesAtOrBeforeHours() >= freeUntilHoursBefore) {
                throw new BusinessException(RecruitmentErrorCode.INVALID_CANCELLATION_POLICY);
            }
        }

        // tier_order 昇順 → applies_at_or_before_hours 降順 の整合性
        List<CancellationPolicyTierRequest> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(CancellationPolicyTierRequest::getTierOrder));
        for (int i = 0; i < sorted.size() - 1; i++) {
            if (sorted.get(i).getAppliesAtOrBeforeHours() <= sorted.get(i + 1).getAppliesAtOrBeforeHours()) {
                throw new BusinessException(RecruitmentErrorCode.TIER_RANGE_OVERLAP);
            }
        }

        // PERCENTAGE 範囲チェック (Bean Validation でカバーされているが二重防御)
        for (CancellationPolicyTierRequest tier : tiers) {
            if (tier.getFeeType() == CancellationFeeType.PERCENTAGE
                    && (tier.getFeeValue() < 1 || tier.getFeeValue() > 100)) {
                throw new BusinessException(RecruitmentErrorCode.INVALID_CANCELLATION_POLICY);
            }
        }
    }

    private double hoursBefore(LocalDateTime startAt, LocalDateTime cancelAt) {
        Duration duration = Duration.between(cancelAt, startAt);
        return duration.toMinutes() / 60.0;
    }

    /**
     * §5.9 キャンセル料計算結果。Service 内ヘルパー型。
     */
    public record CalculatedFee(
            Long policyId,
            Long tierId,
            Integer tierOrder,
            String feeType,
            int feeAmount,
            boolean freeUntilApplied,
            double hoursBefore
    ) {
        public static CalculatedFee free(Long policyId, double hoursBefore) {
            return new CalculatedFee(policyId, null, null, null, 0, true, hoursBefore);
        }
    }
}
