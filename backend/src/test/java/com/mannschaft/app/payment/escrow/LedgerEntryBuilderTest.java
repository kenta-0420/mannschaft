package com.mannschaft.app.payment.escrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T8: 複式記帳の貸借一致（骨格）単体テスト（設計書 01 §3.3）。
 *
 * <p>P2-a では実記帳は走らないため、記帳ヘルパ {@link LedgerEntryBuilder} の
 * 「借方合計＝貸方合計」不変条件を固定する。不一致は症状を隠さず例外で即時失敗する。</p>
 */
@DisplayName("LedgerEntryBuilder 貸借一致単体テスト（T8）")
class LedgerEntryBuilderTest {

    private static final UUID TX_ID = UUID.fromString("019607a0-0000-7000-8000-000000000010");

    @Test
    @DisplayName("正常系: capture/transfer/fee の借方合計＝貸方合計で build 成功")
    void balancedEntriesBuild() {
        // 例: capture 5000 → payee 4500 + platform fee 500（借方 ESCROW 5000 / 貸方 PAYEE+FEE 5000）
        List<LedgerEntryEntity> entries = LedgerEntryBuilder.forTransaction(TX_ID, "JPY")
                .debit(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, 5000, "pi_xxx")
                .credit(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, 4500, "tr_xxx")
                .credit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, 500, null)
                .build();

        assertThat(entries).hasSize(3);
        long debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(debit).isEqualTo(credit).isEqualTo(5000L);
    }

    @Test
    @DisplayName("異常系: 借方≠貸方なら build で IllegalStateException（症状を隠さない）")
    void unbalancedEntriesThrow() {
        LedgerEntryBuilder builder = LedgerEntryBuilder.forTransaction(TX_ID, "JPY")
                .debit(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, 5000, "pi_xxx")
                .credit(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, 4000, "tr_xxx");

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("貸借不一致");
    }

    @Test
    @DisplayName("running_balance は借方=+ / 貸方=- の符号付き累積で 0 に収束")
    void runningBalanceConverges() {
        List<LedgerEntryEntity> entries = LedgerEntryBuilder.forTransaction(TX_ID, "JPY")
                .debit(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, 5000, null)
                .credit(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, 4500, null)
                .credit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, 500, null)
                .build();

        assertThat(entries.get(entries.size() - 1).getRunningBalance()).isZero();
    }

    @Test
    @DisplayName("異常系: 記帳 0 件は build で例外")
    void emptyThrows() {
        assertThatThrownBy(() -> LedgerEntryBuilder.forTransaction(TX_ID, "JPY").build())
                .isInstanceOf(IllegalStateException.class);
    }
}
