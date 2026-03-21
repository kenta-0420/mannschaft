package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.dto.PaymentSummaryResponse;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
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

    /**
     * チーム支払いサマリーを取得する。
     */
    public PaymentSummaryResponse getTeamPaymentSummary(Long teamId) {
        List<PaymentItemEntity> items = paymentItemService.findTeamPaymentItems(teamId);
        List<PaymentSummaryResponse.ItemSummary> summaries = items.stream()
                .map(this::buildItemSummary)
                .toList();

        // TODO: totalMembers はチームメンバー数を取得する（メンバー機能連携後）
        return new PaymentSummaryResponse(teamId, null, 0, summaries);
    }

    /**
     * 組織支払いサマリーを取得する。
     */
    public PaymentSummaryResponse getOrganizationPaymentSummary(Long organizationId) {
        List<PaymentItemEntity> items = paymentItemService.findOrganizationPaymentItems(organizationId);
        List<PaymentSummaryResponse.ItemSummary> summaries = items.stream()
                .map(this::buildItemSummary)
                .toList();

        return new PaymentSummaryResponse(null, organizationId, 0, summaries);
    }

    private PaymentSummaryResponse.ItemSummary buildItemSummary(PaymentItemEntity item) {
        long paidCount = memberPaymentRepository.countByPaymentItemIdAndStatus(item.getId(), PaymentStatus.PAID);
        var totalCollected = memberPaymentRepository.sumPaidAmountByPaymentItemId(item.getId());

        // TODO: unpaidCount = totalMembers - paidCount（メンバー機能連携後）
        return new PaymentSummaryResponse.ItemSummary(
                item.getId(), item.getName(), item.getType().name(),
                item.getAmount(), item.getCurrency(),
                paidCount, 0, totalCollected,
                item.getIsActive(), item.getDisplayOrder()
        );
    }
}
