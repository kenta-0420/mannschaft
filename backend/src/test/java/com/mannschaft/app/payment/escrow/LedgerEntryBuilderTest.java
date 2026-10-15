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

    @Test
    @DisplayName("§6.3🔴: recoveryPair は借貸ペアに recovery_kind を焼き付け借貸一致で build（経路の峻別基盤）")
    void recoveryPairStampsRecoveryKind() {
        // A 回収実行（D PAYEE = C PLATFORM_FEE = 360・A_EXECUTION）。
        List<LedgerEntryEntity> exec = LedgerEntryBuilder.forTransaction(TX_ID, "JPY")
                .recoveryPair(RecoveryKind.A_EXECUTION, LedgerAccount.PAYEE, LedgerAccount.PLATFORM_FEE,
                        360, "pi_xxx")
                .build();
        assertThat(exec).hasSize(2);
        assertThat(exec).allSatisfy(e -> {
            assertThat(e.getEntryType()).isEqualTo(LedgerEntryType.RECOVERY);
            assertThat(e.getRecoveryKind()).isEqualTo(RecoveryKind.A_EXECUTION);
            assertThat(e.getStripeObjectId()).isEqualTo("pi_xxx");
        });
        // 借方=PAYEE / 貸方=PLATFORM_FEE。
        assertThat(exec).filteredOn(e -> e.getDirection() == LedgerDirection.D)
                .singleElement().satisfies(e -> assertThat(e.getAccount()).isEqualTo(LedgerAccount.PAYEE));
        assertThat(exec).filteredOn(e -> e.getDirection() == LedgerDirection.C)
                .singleElement().satisfies(e -> assertThat(e.getAccount()).isEqualTo(LedgerAccount.PLATFORM_FEE));

        // C1 発生計上（D PLATFORM_FEE = C PAYEE = 400・C1_ACCRUAL）は逆向き＋別 kind。
        List<LedgerEntryEntity> c1 = LedgerEntryBuilder.forTransaction(TX_ID, "JPY")
                .recoveryPair(RecoveryKind.C1_ACCRUAL, LedgerAccount.PLATFORM_FEE, LedgerAccount.PAYEE,
                        400, "re_xxx")
                .build();
        assertThat(c1).allSatisfy(e -> assertThat(e.getRecoveryKind()).isEqualTo(RecoveryKind.C1_ACCRUAL));
        assertThat(c1).filteredOn(e -> e.getDirection() == LedgerDirection.C)
                .singleElement().satisfies(e -> assertThat(e.getAccount()).isEqualTo(LedgerAccount.PAYEE));

        // 非 RECOVERY の通常 debit/credit は recovery_kind=null（discriminator 不侵食）。
        List<LedgerEntryEntity> capture = LedgerEntryBuilder.forTransaction(TX_ID, "JPY")
                .debit(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, 5000, "pi_xxx")
                .credit(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, 4500, "pi_xxx")
                .credit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, 500, "pi_xxx")
                .build();
        assertThat(capture).allSatisfy(e -> assertThat(e.getRecoveryKind()).isNull());
    }
}
