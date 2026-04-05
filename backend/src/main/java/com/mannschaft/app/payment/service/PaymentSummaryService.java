package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.dto.PaymentSummaryResponse;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 支払いサマリーサービス。ADMIN ダッシュボード用の集計情報を提供する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentSummaryService {

    private final PaymentItemService paymentItemService;
    private final MemberPaymentRepository memberPaymentRepository;
    private final UserRoleRepository userRoleRepository;

    /**
     * チーム支払いサマリーを取得する。
     */
    public PaymentSummaryResponse getTeamPaymentSummary(Long teamId) {
        List<PaymentItemEntity> items = paymentItemService.findTeamPaymentItems(teamId);
        long totalMembers = userRoleRepository.countByTeamId(teamId);
        List<PaymentSummaryResponse.ItemSummary> summaries = items.stream()
                .map(item -> buildItemSummary(item, totalMembers))
                .toList();

        return new PaymentSummaryResponse(teamId, null, (int) totalMembers, summaries);
    }

    /**
     * 組織支払いサマリーを取得する。
     */
    public PaymentSummaryResponse getOrganizationPaymentSummary(Long organizationId) {
        List<PaymentItemEntity> items = paymentItemService.findOrganizationPaymentItems(organizationId);

        long totalMembers = userRoleRepository.countByOrganizationId(organizationId);
        List<PaymentSummaryResponse.ItemSummary> summaries = items.stream()
                .map(item -> buildItemSummary(item, totalMembers))
                .toList();

        return new PaymentSummaryResponse(null, organizationId, (int) totalMembers, summaries);
    }

    private PaymentSummaryResponse.ItemSummary buildItemSummary(PaymentItemEntity item, long totalMembers) {
        long paidCount = memberPaymentRepository.countByPaymentItemIdAndStatus(item.getId(), PaymentStatus.PAID);
        var totalCollected = memberPaymentRepository.sumPaidAmountByPaymentItemId(item.getId());

        long unpaidCount = Math.max(0, totalMembers - paidCount);
        return new PaymentSummaryResponse.ItemSummary(
                item.getId(), item.getName(), item.getType().name(),
                item.getAmount(), item.getCurrency(),
                paidCount, unpaidCount, totalCollected,
                item.getIsActive(), item.getDisplayOrder()
        );
    }
}
