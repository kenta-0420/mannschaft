package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.service.MembershipPayerWithdrawalRunner.Outcome;
import com.mannschaft.app.payment.service.MembershipPayerWithdrawalTxService.PreparedTarget;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 柱③-B（CMP-260901-1538）PR-3: {@link MembershipPayerWithdrawalRunner} の<b>手順</b>の単体テスト。
 *
 * <h2>本テストが担う範囲と、担わない範囲</h2>
 * <p>ここで固定するのは「Stripe とトランザクションの<b>呼び出し順序と失敗時の分岐</b>」だけである。
 * すなわち ①予約着手を commit してから Stripe を呼ぶこと、②Stripe 失敗時に DB 反映へ進まず失敗を
 * 永続化すること、③Stripe 成功後の DB 失敗も失敗として永続化すること。</p>
 *
 * <p>実際にトランザクションが分離しているか・行ロックが効くか・巻き添えロールバックが起きないかは
 * <b>モックでは原理的に検証できない</b>。それは
 * {@code com.mannschaft.app.payment.MembershipPayerWithdrawalCancelIT}（実 MySQL）が受け持つ。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipPayerWithdrawalRunner（柱③-B PR-3・退会時の一括期末解約の手順）")
class MembershipSubscriptionPayerWithdrawalTest {

    private static final UUID SUB_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000d1");
    private static final Long PAYER_ID = 5001L;
    private static final String STRIPE_SUB = "sub_withdrawal_unit";

    @Mock private MembershipPayerWithdrawalTxService txService;
    @Mock private StripePaymentProvider stripePaymentProvider;

    @InjectMocks private MembershipPayerWithdrawalRunner runner;

    @Nested
    @DisplayName("期末解約（cancelOne）")
    class CancelOne {

        @Test
        @DisplayName("正常系: 予約着手 → Stripe → DB 反映の順に進み SCHEDULED を返す")
        void 正常_三段の順に進む() {
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB)));
            when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenReturn(new StripePaymentProvider.SubscriptionInfo(STRIPE_SUB, "active", 1_800_000_000L));
            when(txService.applyScheduled(SUB_ID, PAYER_ID, 1_800_000_000L)).thenReturn(true);

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.SCHEDULED);
        }

        @Test
        @DisplayName("対象外: 予約着手が空を返したら Stripe を叩かず SKIPPED")
        void 対象外_Stripeを叩かない() {
            when(txService.prepare(SUB_ID, PAYER_ID)).thenReturn(Optional.empty());

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.SKIPPED);
            verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
            verify(txService, never()).applyScheduled(any(), any(), any());
        }

        @Test
        @DisplayName("Stripe 未連結: Stripe を叩かずに DB のみ反映する")
        void Stripe未連結_DBのみ反映する() {
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, null)));
            when(txService.applyScheduled(SUB_ID, PAYER_ID, null)).thenReturn(true);

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.SCHEDULED);
            verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
        }

        @Test
        @DisplayName("Stripe 失敗: DB 反映へ進まず、失敗を永続化して FAILED を返す")
        void Stripe失敗_失敗を永続化する() {
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB)));
            when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenThrow(new IllegalStateException("Stripe 障害"));

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.FAILED);
            verify(txService, never()).applyScheduled(any(), any(), any());
            // ログだけでは PR-4 の再試行バッチが拾えない。DB に残すことが要点。
            verify(txService).markFailed(eq(SUB_ID), anyString());
        }

        @Test
        @DisplayName("Stripe 成功後の DB 失敗: 失敗を永続化して FAILED を返す（Stripe との乖離を残さない）")
        void DB失敗_失敗を永続化する() {
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB)));
            when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenReturn(new StripePaymentProvider.SubscriptionInfo(STRIPE_SUB, "active", null));
            when(txService.applyScheduled(SUB_ID, PAYER_ID, null))
                    .thenThrow(new IllegalStateException("DB 障害"));

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.FAILED);
            verify(txService).markFailed(eq(SUB_ID), anyString());
        }

        @Test
        @DisplayName("例外を外へ投げない: 1件の失敗で呼び出し元のループを止めない")
        void 例外を伝播しない() {
            when(txService.prepare(SUB_ID, PAYER_ID)).thenThrow(new IllegalStateException("ロック取得失敗"));

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.FAILED);
        }
    }

    @Nested
    @DisplayName("退会取消による復旧（restoreOne）")
    class RestoreOne {

        @Test
        @DisplayName("正常系: Stripe の期末解約を解除してから DB を戻す")
        void 正常_Stripeを先に解除する() {
            when(txService.prepareRestore(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB)));
            when(txService.applyRestore(SUB_ID, PAYER_ID)).thenReturn(true);

            assertThat(runner.restoreOne(SUB_ID, PAYER_ID)).isTrue();
            verify(stripePaymentProvider).revertSubscriptionCancelAtPeriodEnd(eq(STRIPE_SUB), anyString());
        }

        @Test
        @DisplayName("対象外: 退会処理由来でなければ Stripe も DB も触らない")
        void 対象外_何もしない() {
            when(txService.prepareRestore(SUB_ID, PAYER_ID)).thenReturn(Optional.empty());

            assertThat(runner.restoreOne(SUB_ID, PAYER_ID)).isFalse();
            verify(stripePaymentProvider, never())
                    .revertSubscriptionCancelAtPeriodEnd(anyString(), anyString());
            verify(txService, never()).applyRestore(any(), any());
        }

        @Test
        @DisplayName("Stripe 失敗: DB を戻さない（Stripe が期末解約のまま乖離するのを防ぐ）")
        void Stripe失敗_DBを戻さない() {
            when(txService.prepareRestore(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB)));
            when(stripePaymentProvider.revertSubscriptionCancelAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenThrow(new IllegalStateException("Stripe 障害"));

            assertThat(runner.restoreOne(SUB_ID, PAYER_ID)).isFalse();
            verify(txService, never()).applyRestore(any(), any());
        }
    }
}
