package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.dto.FeeStatementResponse;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * FeeStatementService ユニットテスト（F08.9 P8 T-FS-01〜05）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>T-FS-01: 正常系 — 当月の手数料が集計されて返ること</li>
 *   <li>T-FS-02: 正常系 — period=null の場合に当月がデフォルトとして使われること</li>
 *   <li>T-FS-03: 正常系 — 対象なし（null 返却）の場合は {@code totalFeeAmount=0} が返ること</li>
 *   <li>T-FS-04: {@code issuerName} が "Mannschaft" であること</li>
 *   <li>T-FS-05: Connect アカウントが存在しない場合は {@link BusinessException} をスローすること</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeeStatementService ユニットテスト（T-FS-01〜05）")
class FeeStatementServiceTest {

    private static final Long TEAM_ID = 100L;
    private static final UUID CONNECT_ACCOUNT_ID = UUID.fromString("018f6c3a-0000-7000-8000-000000000001");
    private static final YearMonth TARGET_PERIOD = YearMonth.of(2026, 6);

    @Mock
    private ConnectAccountRepository connectAccountRepository;

    @Mock
    private EscrowTransactionRepository escrowTransactionRepository;

    @InjectMocks
    private FeeStatementService feeStatementService;

    /**
     * テスト用 ConnectAccountEntity を生成する。
     */
    private ConnectAccountEntity buildConnectAccount() {
        ConnectAccountEntity account = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM)
                .scopeId(TEAM_ID)
                .stripeAccountId("acct_test_001")
                .country("JP")
                .defaultCurrency("JPY")
                .chargesEnabled(true)
                .payoutsEnabled(true)
                .build();
        account.setId(CONNECT_ACCOUNT_ID);
        return account;
    }

    // -------------------------------------------------------------------------
    // T-FS-01: 正常系 — 当月の手数料が集計されて返ること
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-FS-01: 指定月の手数料が集計されて返る")
    void getTeamFeeStatement_withPeriod_returnsAggregated() {
        ConnectAccountEntity account = buildConnectAccount();
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                .willReturn(Optional.of(account));
        given(escrowTransactionRepository.sumApplicationFeeByPayeeConnectAccountAndPeriod(
                CONNECT_ACCOUNT_ID, TARGET_PERIOD.getYear(), TARGET_PERIOD.getMonthValue()))
                .willReturn(5000L);

        FeeStatementResponse result = feeStatementService.getTeamFeeStatement(TEAM_ID, TARGET_PERIOD);

        assertThat(result).isNotNull();
        assertThat(result.getPeriod()).isEqualTo(TARGET_PERIOD);
        assertThat(result.getTotalFeeAmount()).isEqualTo(5000L);
        assertThat(result.getCurrency()).isEqualTo("JPY");
    }

    // -------------------------------------------------------------------------
    // T-FS-02: 正常系 — period=null の場合に当月がデフォルトとして使われること
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-FS-02: period=null のとき当月がデフォルトとして集計される")
    void getTeamFeeStatement_periodNull_usesCurrentMonth() {
        ConnectAccountEntity account = buildConnectAccount();
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                .willReturn(Optional.of(account));
        given(escrowTransactionRepository.sumApplicationFeeByPayeeConnectAccountAndPeriod(
                eq(CONNECT_ACCOUNT_ID), any(Integer.class), any(Integer.class)))
                .willReturn(3000L);

        FeeStatementResponse result = feeStatementService.getTeamFeeStatement(TEAM_ID, null);

        assertThat(result).isNotNull();
        assertThat(result.getPeriod()).isEqualTo(YearMonth.now());
        assertThat(result.getTotalFeeAmount()).isEqualTo(3000L);
    }

    // -------------------------------------------------------------------------
    // T-FS-03: 正常系 — 対象なし（null / 0 返却）の場合は totalFeeAmount=0
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-FS-03: 対象取引なし（null 返却）の場合は totalFeeAmount=0 が返る")
    void getTeamFeeStatement_noTransactions_returnsTotalZero() {
        ConnectAccountEntity account = buildConnectAccount();
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                .willReturn(Optional.of(account));
        // COALESCE で 0 が返る想定（テスト環境では null をシミュレート）
        given(escrowTransactionRepository.sumApplicationFeeByPayeeConnectAccountAndPeriod(
                CONNECT_ACCOUNT_ID, TARGET_PERIOD.getYear(), TARGET_PERIOD.getMonthValue()))
                .willReturn(null);

        FeeStatementResponse result = feeStatementService.getTeamFeeStatement(TEAM_ID, TARGET_PERIOD);

        assertThat(result.getTotalFeeAmount()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // T-FS-04: issuerName が "Mannschaft" であること
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-FS-04: issuerName は常に \"Mannschaft\" である")
    void getTeamFeeStatement_issuerName_isMannschaft() {
        ConnectAccountEntity account = buildConnectAccount();
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                .willReturn(Optional.of(account));
        given(escrowTransactionRepository.sumApplicationFeeByPayeeConnectAccountAndPeriod(
                CONNECT_ACCOUNT_ID, TARGET_PERIOD.getYear(), TARGET_PERIOD.getMonthValue()))
                .willReturn(0L);

        FeeStatementResponse result = feeStatementService.getTeamFeeStatement(TEAM_ID, TARGET_PERIOD);

        assertThat(result.getIssuerName()).isEqualTo("Mannschaft");
    }

    // -------------------------------------------------------------------------
    // T-FS-05: Connect アカウントが存在しない場合は BusinessException をスロー
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-FS-05: Connect アカウントが存在しない場合は BusinessException（PAYMENT_C002）をスロー")
    void getTeamFeeStatement_noConnectAccount_throwsBusinessException() {
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> feeStatementService.getTeamFeeStatement(TEAM_ID, TARGET_PERIOD))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
                });
    }
}
