package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.dto.AccessRequirementsRequest;
import com.mannschaft.app.payment.dto.AccessRequirementsResponse;
import com.mannschaft.app.payment.entity.OrganizationAccessRequirementEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.TeamAccessRequirementEntity;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * アクセス要件サービス。チーム/組織全体ロックの設定・取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessRequirementService {

    private final TeamAccessRequirementRepository teamAccessRequirementRepository;
    private final OrganizationAccessRequirementRepository organizationAccessRequirementRepository;
    private final PaymentItemService paymentItemService;

    /**
     * チーム全体ロック設定を取得する。
     */
    public AccessRequirementsResponse getTeamAccessRequirements(Long teamId) {
        List<TeamAccessRequirementEntity> requirements = teamAccessRequirementRepository.findByTeamId(teamId);
        List<AccessRequirementsResponse.PaymentItemRef> items = requirements.stream()
                .map(req -> {
                    PaymentItemEntity item = paymentItemService.findByIdOrThrow(req.getPaymentItemId());
                    return new AccessRequirementsResponse.PaymentItemRef(item.getId(), item.getName());
                })
                .toList();
        return new AccessRequirementsResponse(teamId, null, items);
    }

    /**
     * チーム全体ロック設定を一括更新する。
     */
    @Transactional
    public AccessRequirementsResponse setTeamAccessRequirements(Long teamId, AccessRequirementsRequest request) {
        // バリデーション
        List<PaymentItemEntity> items = validatePaymentItemsForAccess(request.getPaymentItemIds(), teamId, null);

        // 既存を全削除して再作成
        teamAccessRequirementRepository.deleteByTeamId(teamId);

        for (Long paymentItemId : request.getPaymentItemIds()) {
            teamAccessRequirementRepository.save(TeamAccessRequirementEntity.builder()
                    .teamId(teamId)
                    .paymentItemId(paymentItemId)
                    .build());
        }

        List<AccessRequirementsResponse.PaymentItemRef> refs = items.stream()
                .map(item -> new AccessRequirementsResponse.PaymentItemRef(item.getId(), item.getName()))
                .toList();

        log.info("チームアクセス要件設定: teamId={}, items={}", teamId, request.getPaymentItemIds());
        return new AccessRequirementsResponse(teamId, null, refs);
    }

    /**
     * 組織全体ロック設定を取得する。
     */
    public AccessRequirementsResponse getOrganizationAccessRequirements(Long organizationId) {
        List<OrganizationAccessRequirementEntity> requirements =
                organizationAccessRequirementRepository.findByOrganizationId(organizationId);
        List<AccessRequirementsResponse.PaymentItemRef> items = requirements.stream()
                .map(req -> {
                    PaymentItemEntity item = paymentItemService.findByIdOrThrow(req.getPaymentItemId());
                    return new AccessRequirementsResponse.PaymentItemRef(item.getId(), item.getName());
                })
                .toList();
        return new AccessRequirementsResponse(null, organizationId, items);
    }

    /**
     * 組織全体ロック設定を一括更新する。
     */
    @Transactional
    public AccessRequirementsResponse setOrganizationAccessRequirements(Long organizationId,
                                                                        AccessRequirementsRequest request) {
        List<PaymentItemEntity> items = validatePaymentItemsForAccess(request.getPaymentItemIds(), null, organizationId);

        organizationAccessRequirementRepository.deleteByOrganizationId(organizationId);

        for (Long paymentItemId : request.getPaymentItemIds()) {
            organizationAccessRequirementRepository.save(OrganizationAccessRequirementEntity.builder()
                    .organizationId(organizationId)
                    .paymentItemId(paymentItemId)
                    .build());
        }

        List<AccessRequirementsResponse.PaymentItemRef> refs = items.stream()
                .map(item -> new AccessRequirementsResponse.PaymentItemRef(item.getId(), item.getName()))
                .toList();

        log.info("組織アクセス要件設定: orgId={}, items={}", organizationId, request.getPaymentItemIds());
        return new AccessRequirementsResponse(null, organizationId, refs);
    }

    /**
     * 支払い項目がアクセス制御に設定可能かを検証する。
     */
    private List<PaymentItemEntity> validatePaymentItemsForAccess(List<Long> paymentItemIds,
                                                                   Long teamId, Long organizationId) {
        return paymentItemIds.stream()
                .map(id -> {
                    PaymentItemEntity item = paymentItemService.findByIdOrThrow(id);

                    // DONATION はアクセス制御に設定不可
                    if (item.getType() == PaymentItemType.DONATION) {
                        throw new BusinessException(PaymentErrorCode.DONATION_NOT_ALLOWED_FOR_ACCESS);
                    }

                    // スコープ検証
                    if (teamId != null && !teamId.equals(item.getTeamId())) {
                        throw new BusinessException(PaymentErrorCode.PAYMENT_ITEM_SCOPE_MISMATCH);
                    }
                    if (organizationId != null && !organizationId.equals(item.getOrganizationId())) {
                        throw new BusinessException(PaymentErrorCode.PAYMENT_ITEM_SCOPE_MISMATCH);
                    }

                    return item;
                })
                .toList();
    }
}
