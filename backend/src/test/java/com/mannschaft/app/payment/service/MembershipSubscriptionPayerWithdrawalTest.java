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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    /** 退会試行の世代。tx① から Stripe を跨いで tx② まで持ち回り、そこで再検証される。 */
    private static final java.time.Instant GEN = java.time.Instant.parse("2026-09-08T00:00:00Z");
    /** 退会試行ごとに一度だけ払い出される不変トークン（世代Aぶん）。 */
    private static final UUID TOKEN_A = UUID.fromString("019607a0-0000-7000-8000-00000000aaaa");
    /** 別の退会試行（世代Bぶん）のトークン。 */
    private static final UUID TOKEN_B = UUID.fromString("019607a0-0000-7000-8000-00000000bbbb");

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
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));
            when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenReturn(new StripePaymentProvider.SubscriptionInfo(STRIPE_SUB, "active", 1_800_000_000L));
            when(txService.applyScheduled(SUB_ID, PAYER_ID, GEN, 1_800_000_000L))
                    .thenReturn(MembershipPayerWithdrawalTxService.ApplyOutcome.APPLIED);

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.SCHEDULED);
        }

        @Test
        @DisplayName("対象外: 予約着手が空を返したら Stripe を叩かず SKIPPED")
        void 対象外_Stripeを叩かない() {
            when(txService.prepare(SUB_ID, PAYER_ID)).thenReturn(Optional.empty());

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.SKIPPED);
            verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
            verify(txService, never()).applyScheduled(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Stripe 未連結: Stripe を叩かずに DB のみ反映する")
        void Stripe未連結_DBのみ反映する() {
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, null, GEN, TOKEN_A)));
            when(txService.applyScheduled(SUB_ID, PAYER_ID, GEN, null))
                    .thenReturn(MembershipPayerWithdrawalTxService.ApplyOutcome.APPLIED);

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.SCHEDULED);
            verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
        }

        @Test
        @DisplayName("Stripe 失敗: DB 反映へ進まず、失敗を永続化して FAILED を返す")
        void Stripe失敗_失敗を永続化する() {
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));
            when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenThrow(new IllegalStateException("Stripe 障害"));

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.FAILED);
            verify(txService, never()).applyScheduled(any(), any(), any(), any());
            // ログだけでは PR-4 の再試行バッチが拾えない。DB に残すことが要点。
            verify(txService).markFailed(eq(SUB_ID), eq(GEN), anyString());
        }

        @Test
        @DisplayName("Stripe 成功後の DB 失敗: 失敗を永続化して FAILED を返す（Stripe との乖離を残さない）")
        void DB失敗_失敗を永続化する() {
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));
            when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenReturn(new StripePaymentProvider.SubscriptionInfo(STRIPE_SUB, "active", null));
            when(txService.applyScheduled(SUB_ID, PAYER_ID, GEN, null))
                    .thenThrow(new IllegalStateException("DB 障害"));

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.FAILED);
            verify(txService).markFailed(eq(SUB_ID), eq(GEN), anyString());
        }

        @Test
        @DisplayName("例外を外へ投げない: 1件の失敗で呼び出し元のループを止めない")
        void 例外を伝播しない() {
            when(txService.prepare(SUB_ID, PAYER_ID)).thenThrow(new IllegalStateException("ロック取得失敗"));

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.FAILED);
        }

        @Test
        @DisplayName("世代変化: Stripe 呼び出し中に退会が取り消されたら、そのまま予約の取り消しへ切り替える")
        void 世代変化_取り消しへ切り替える() {
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));
            when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenReturn(new StripePaymentProvider.SubscriptionInfo(STRIPE_SUB, "active", null));
            when(txService.applyScheduled(SUB_ID, PAYER_ID, GEN, null)).thenReturn(
                    MembershipPayerWithdrawalTxService.ApplyOutcome.ABORTED_GENERATION_CHANGED);
            // 切り替え先の復旧経路（対象外で空を返しても、呼ばれること自体が要点）。
            when(txService.prepareRestore(SUB_ID, PAYER_ID)).thenReturn(Optional.empty());

            assertThat(runner.cancelOne(SUB_ID, PAYER_ID)).isEqualTo(Outcome.ABORTED);
            // 放置すると「退会取消済みなのに期末で終了する」が残る。必ず取り消しへ進む。
            verify(txService).prepareRestore(SUB_ID, PAYER_ID);
        }
    }

    @Nested
    @DisplayName("退会取消による復旧（restoreOne）")
    class RestoreOne {

        @Test
        @DisplayName("正常系: Stripe の期末解約を解除してから DB を戻す")
        void 正常_Stripeを先に解除する() {
            when(txService.prepareRestore(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));
            when(txService.applyRestore(SUB_ID, PAYER_ID, GEN)).thenReturn(true);

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
            verify(txService, never()).applyRestore(any(), any(), any());
        }

        @Test
        @DisplayName("Stripe 失敗: DB を戻さず、復旧失敗を RESTORING のまま永続化する")
        void Stripe失敗_DBを戻さない() {
            when(txService.prepareRestore(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));
            when(stripePaymentProvider.revertSubscriptionCancelAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenThrow(new IllegalStateException("Stripe 障害"));

            assertThat(runner.restoreOne(SUB_ID, PAYER_ID)).isFalse();
            verify(txService, never()).applyRestore(any(), any(), any());
            verify(txService).markRestoreFailed(eq(SUB_ID), eq(GEN), anyString());
        }

        @Test
        @DisplayName("Stripe 成功後の DB 失敗: FAILED ではなく RESTORING のまま残す（解約未了と混同しない）")
        void DB失敗_RESTORINGのまま残す() {
            when(txService.prepareRestore(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));
            when(txService.applyRestore(SUB_ID, PAYER_ID, GEN))
                    .thenThrow(new IllegalStateException("DB 障害"));

            assertThat(runner.restoreOne(SUB_ID, PAYER_ID)).isFalse();
            // FAILED は「解約が未了」を意味し、再試行バッチの扱いが逆向きになる。
            verify(txService, never()).markFailed(any(), any(), anyString());
            verify(txService).markRestoreFailed(eq(SUB_ID), eq(GEN), anyString());
        }
    }

    @Nested
    @DisplayName("Stripe 冪等キーの世代分離（検分4巡目 P1-1）")
    class IdempotencyKeyGeneration {

        @Test
        @DisplayName("解約: 同一世代の再試行では冪等キーが【変わらない】（Stripe が二重実行しない）")
        void 解約_同一世代の再試行ではキーが固定される() {
            org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
            when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenReturn(new StripePaymentProvider.SubscriptionInfo(STRIPE_SUB, "active", null));
            when(txService.applyScheduled(eq(SUB_ID), eq(PAYER_ID), any(), any()))
                    .thenReturn(MembershipPayerWithdrawalTxService.ApplyOutcome.APPLIED);
            // 同一世代の再送・再試行では作業行のトークンは払い直されないので、同じ値が渡る。
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));

            runner.cancelOne(SUB_ID, PAYER_ID);
            runner.cancelOne(SUB_ID, PAYER_ID);

            verify(stripePaymentProvider, org.mockito.Mockito.times(2))
                    .cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), keys.capture());
            // 是正前は試行番号を使っていたため n → n+1 とキーが変わり、結果不明の再試行が
            // Stripe で重複排除されず【二重実行】になりえた。
            assertThat(keys.getAllValues()).containsExactly(
                    keys.getAllValues().get(0), keys.getAllValues().get(0));
        }

        @Test
        @DisplayName("解約: 【同一秒の】別世代ではトークンが払い直され冪等キーが分かれる")
        void 解約_世代ごとに冪等キーが異なる() {
            org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
            when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), anyString()))
                    .thenReturn(new StripePaymentProvider.SubscriptionInfo(STRIPE_SUB, "active", null));
            when(txService.applyScheduled(eq(SUB_ID), eq(PAYER_ID), any(), any()))
                    .thenReturn(MembershipPayerWithdrawalTxService.ApplyOutcome.APPLIED);

            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));
            runner.cancelOne(SUB_ID, PAYER_ID);
            when(txService.prepare(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_B)));
            runner.cancelOne(SUB_ID, PAYER_ID);

            verify(stripePaymentProvider, org.mockito.Mockito.times(2))
                    .cancelSubscriptionAtPeriodEnd(eq(STRIPE_SUB), keys.capture());
            // 本番の users.deleted_at は DATETIME（秒精度）であり、同一秒内の
            // 「退会A → 取消 → 再退会B」では世代値が同一になる（GEN は両者で同じ値を渡している）。
            // 世代が変わったときだけ払い直されるトークンで分ける。
            assertThat(keys.getAllValues()).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("復旧: 解約キーと衝突せず、別世代では分かれる")
        void 復旧_世代ごとに冪等キーが異なる() {
            org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
            when(txService.applyRestore(eq(SUB_ID), eq(PAYER_ID), any())).thenReturn(true);

            when(txService.prepareRestore(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_A)));
            runner.restoreOne(SUB_ID, PAYER_ID);
            when(txService.prepareRestore(SUB_ID, PAYER_ID))
                    .thenReturn(Optional.of(new PreparedTarget(SUB_ID, STRIPE_SUB, GEN, TOKEN_B)));
            runner.restoreOne(SUB_ID, PAYER_ID);

            verify(stripePaymentProvider, org.mockito.Mockito.times(2))
                    .revertSubscriptionCancelAtPeriodEnd(eq(STRIPE_SUB), keys.capture());
            assertThat(keys.getAllValues()).doesNotHaveDuplicates();
            assertThat(keys.getAllValues()).allSatisfy(k ->
                    assertThat(k).startsWith("withdrawal-payer-restore-"));
        }
    }

    @Nested
    @DisplayName("冪等トークンの払い出し規則（作業行・絞り込み確認 P1）")
    class AttemptTokenRotation {

        private static final java.time.Instant GEN_LATER =
                java.time.Instant.parse("2026-10-01T00:00:00Z");

        private com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationEntity newRecord() {
            return com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationEntity.builder()
                    .subscriptionId(SUB_ID).payerUserId(PAYER_ID).attemptCount(0).build();
        }

        @Test
        @DisplayName("同一世代の再試行ではトークンを払い直さない（＝冪等キーが固定される）")
        void 同一世代ではトークンが変わらない() {
            var record = newRecord();
            record.markAttempt(GEN, STRIPE_SUB);
            UUID first = record.getWithdrawalAttemptToken();

            record.markAttempt(GEN, STRIPE_SUB);
            record.reserveForGeneration(GEN);

            assertThat(first).isNotNull();
            assertThat(record.getWithdrawalAttemptToken()).isEqualTo(first);
        }

        @Test
        @DisplayName("世代が変われば新しいトークンを払い出す（＝再退会は別のキーになる）")
        void 別世代ではトークンが変わる() {
            var record = newRecord();
            record.markAttempt(GEN, STRIPE_SUB);
            UUID first = record.getWithdrawalAttemptToken();

            record.markAttempt(GEN_LATER, STRIPE_SUB);

            assertThat(record.getWithdrawalAttemptToken()).isNotNull().isNotEqualTo(first);
        }

        @Test
        @DisplayName("reserveForGeneration でも世代が変われば払い直す")
        void reserveでも世代変化で払い直す() {
            var record = newRecord();
            record.reserveForGeneration(GEN);
            UUID first = record.getWithdrawalAttemptToken();

            record.reserveForGeneration(GEN_LATER);

            assertThat(record.getWithdrawalAttemptToken()).isNotNull().isNotEqualTo(first);
        }
    }

    @Nested
    @DisplayName("作業行の先行永続化（reserveAll・検分2巡目 P1-2）")
    class ReserveAll {

        @Test
        @DisplayName("正常系: トランザクション単位へそのまま委譲する")
        void 正常_委譲する() {
            when(txService.reserveAll(List.of(SUB_ID), PAYER_ID)).thenReturn(List.of(SUB_ID));

            assertThat(runner.reserveAll(List.of(SUB_ID), PAYER_ID)).containsExactly(SUB_ID);
        }

        @Test
        @DisplayName("異常系: 先行永続化に失敗しても例外を投げず空を返す（呼び出し元は0件として扱う）")
        void 異常_例外を伝播しない() {
            when(txService.reserveAll(List.of(SUB_ID), PAYER_ID))
                    .thenThrow(new IllegalStateException("DB 障害"));

            assertThat(runner.reserveAll(List.of(SUB_ID), PAYER_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("本人の明示操作による由来の無効化（supersede・検分2巡目 P1-1）")
    class Supersede {

        @Test
        @DisplayName("正常系: トランザクション単位へ委譲する")
        void 正常_委譲する() {
            runner.supersedeByUserDecision(SUB_ID);

            verify(txService).supersedeByUserDecision(SUB_ID);
        }

        @Test
        @DisplayName("異常系: 無効化に失敗しても解約 API を道連れにしない")
        void 異常_例外を伝播しない() {
            org.mockito.Mockito.doThrow(new IllegalStateException("DB 障害"))
                    .when(txService).supersedeByUserDecision(SUB_ID);

            assertThatCode(() -> runner.supersedeByUserDecision(SUB_ID)).doesNotThrowAnyException();
        }
    }
}
