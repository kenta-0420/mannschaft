package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.dto.FeeStatementResponse;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.UUID;

/**
 * F08.9 P8 月次手数料明細サービス。
 *
 * <p>チームの Connect アカウントに紐づく {@code escrow_transactions.application_fee_amount} を
 * 月単位で集計し、Mannschaft 名義の手数料明細を返す（仕入税額控除の枠・税からくり）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §8.2</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeeStatementService {

    private final ConnectAccountRepository connectAccountRepository;
    private final EscrowTransactionRepository escrowTransactionRepository;

    /**
     * チームの月次手数料明細を取得する。
     *
     * @param teamId チーム ID
     * @param period 集計対象月。{@code null} の場合は当月をデフォルトとする
     * @return 手数料明細レスポンス（取引 0 件の場合は {@code totalFeeAmount=0}）
     * @throws BusinessException チームに有効な Connect アカウントが存在しない場合（{@link ConnectPaymentErrorCode#PAYMENT_RESOURCE_NOT_FOUND}）
     */
    public FeeStatementResponse getTeamFeeStatement(Long teamId, YearMonth period) {
        YearMonth targetPeriod = (period != null) ? period : YearMonth.now();

        ConnectAccountEntity connectAccount = connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, teamId)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        UUID payeeConnectAccountId = connectAccount.getId();

        Long totalFeeAmount = escrowTransactionRepository.sumApplicationFeeByPayeeConnectAccountAndPeriod(
                payeeConnectAccountId,
                targetPeriod.getYear(),
                targetPeriod.getMonthValue());

        return FeeStatementResponse.builder()
                .period(targetPeriod)
                .totalFeeAmount(totalFeeAmount != null ? totalFeeAmount : 0L)
                .currency("JPY")
                .issuerName("Mannschaft")
                .build();
    }
}
