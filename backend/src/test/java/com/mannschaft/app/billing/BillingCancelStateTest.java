package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR6a AC-60 / AC-63 / AC-65: canCancel / canResume の導出（Stripe に一切触れない純関数）。
 */
@DisplayName("PR6a 解約可否の導出（AC-60 / AC-63）")
class BillingCancelStateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.SEPTEMBER, 12, 12, 0);
    private static final LocalDateTime FUTURE = NOW.plusDays(10);
    private static final LocalDateTime PAST = NOW.minusDays(10);

    @Test
    @DisplayName("AC-60: 解約予約前の ACTIVE は canCancel=true / canResume=false")
    void 予約前() {
        assertThat(BillingCancelState.canCancel(ContractStatus.ACTIVE, null)).isTrue();
        assertThat(BillingCancelState.canResume(ContractStatus.ACTIVE, null, FUTURE, NOW)).isFalse();
        assertThat(BillingCancelState.scheduled(ContractStatus.ACTIVE, null)).isFalse();
    }

    @Test
    @DisplayName("AC-60: 解約予約後は canCancel=false / canResume=true")
    void 予約後() {
        assertThat(BillingCancelState.canCancel(ContractStatus.ACTIVE, PAST)).isFalse();
        assertThat(BillingCancelState.canResume(ContractStatus.ACTIVE, PAST, FUTURE, NOW)).isTrue();
        assertThat(BillingCancelState.scheduled(ContractStatus.ACTIVE, PAST)).isTrue();
    }

    @Test
    @DisplayName("AC-46: 期末を跨いだら撤回できない（期末ちょうども不可・半開区間）")
    void 期末を跨いだら撤回不可() {
        assertThat(BillingCancelState.canResume(ContractStatus.ACTIVE, PAST, PAST, NOW)).isFalse();
        assertThat(BillingCancelState.canResume(ContractStatus.ACTIVE, PAST, NOW, NOW)).isFalse();
        assertThat(BillingCancelState.canResume(ContractStatus.ACTIVE, PAST, null, NOW)).isFalse();
    }

    @Test
    @DisplayName("D4: PAST_DUE も操作できる。PENDING / CANCELLED / EXPIRED / PENDING_HANDOVER は操作できない")
    void 操作可能な状態() {
        assertThat(BillingCancelState.canCancel(ContractStatus.PAST_DUE, null)).isTrue();
        assertThat(BillingCancelState.canCancel(ContractStatus.PENDING, null)).isFalse();
        assertThat(BillingCancelState.canCancel(ContractStatus.CANCELLED, null)).isFalse();
        assertThat(BillingCancelState.canCancel(ContractStatus.EXPIRED, null)).isFalse();
        assertThat(BillingCancelState.canCancel(ContractStatus.PENDING_HANDOVER, null)).isFalse();
    }

    @Test
    @DisplayName("AC-23: 既に CANCELLED / EXPIRED へ確定した契約の cancelled_at は「予約」ではない")
    void 確定済みは予約として扱わない() {
        assertThat(BillingCancelState.scheduled(ContractStatus.EXPIRED, PAST)).isFalse();
        assertThat(BillingCancelState.canResume(ContractStatus.EXPIRED, PAST, FUTURE, NOW)).isFalse();
    }
}
