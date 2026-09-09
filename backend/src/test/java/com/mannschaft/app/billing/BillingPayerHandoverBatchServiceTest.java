package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.willThrow;

/**
 * 柱③-B PR-4（CMP-260901-1538）: 夜次バッチの<b>駆動</b>を固定する単体テスト（試練先行）。
 *
 * <p>PR-2 は抽出クエリと解決メソッドだけを提供し {@code @Scheduled} を結線しなかった。
 * 本 PR がそれを結線するまで、引継は<b>自動では一切前へ進まない</b>（切替も照合も誰も呼ばない）。
 * したがってここで固定すべき不変条件は「抽出 → 1件ずつ独立に解決 → 1件の失敗が他を巻き込まない」
 * という駆動の形そのものである。</p>
 *
 * <p>対象 AC: AC-27（切替の駆動）/ AC-34（{@code cancel_at_period_end} の夜次照合）/
 * AC-35（{@code MANUAL_INTERVENTION} への分岐は {@code executeSwitch} 側）/ §5.3（期限超過の照合）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingPayerHandoverBatchService 単体テスト（引継の夜次バッチ駆動）")
class BillingPayerHandoverBatchServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-09T02:40:00Z"), ZoneOffset.UTC);

    @Mock private BillingPayerHandoverService handoverService;

    private BillingPayerHandoverBatchService batchService;

    private BillingPayerHandoverBatchService service() {
        if (batchService == null) {
            batchService = new BillingPayerHandoverBatchService(handoverService, FIXED_CLOCK);
        }
        return batchService;
    }

    /**
     * 滞留照合はキーセットで進むため、既定では「1ページ目が空」を返す。
     * 個々のテストで進み方を検証したい場合だけ上書きする。
     */
    private void givenNoStalledSwitching() {
        given(handoverService.findStalledSwitchingPage(any(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(new BillingPayerHandoverService.StalledSwitchingPage(List.of(), null));
    }

    @Test
    @DisplayName("AC-27: 切替バッチは抽出した全件に対して executeSwitch を呼ぶ（PR-2 の未結線を解消する）")
    void switchBatch_drivesEveryDueHandover() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        given(handoverService.findSwitchDueHandoverIds(any())).willReturn(List.of(a, b));

        service().runPayerHandoverSwitch();

        verify(handoverService).executeSwitch(a);
        verify(handoverService).executeSwitch(b);
    }

    @Test
    @DisplayName("1件の失敗が他件を巻き込まない（バッチ全体を1トランザクションにしない形の担保）")
    void switchBatch_oneFailureDoesNotStopOthers() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        given(handoverService.findSwitchDueHandoverIds(any())).willReturn(List.of(failing, healthy));
        willThrow(new IllegalStateException("boom")).given(handoverService).executeSwitch(failing);

        service().runPayerHandoverSwitch();

        verify(handoverService).executeSwitch(healthy);
    }

    @Test
    @DisplayName("AC-21: 猶予期限を過ぎたまま誰にも承諾されなかった要求を EXPIRED で終端化する"
            + "（承諾操作が来ない限り誰も期限切れにしなかった穴を塞ぐ）")
    void nightlyReconcile_expiresOverdueUnaccepted() {
        UUID target = UUID.randomUUID();
        givenNoStalledSwitching();
        given(handoverService.findOverdueUnacceptedIds(any())).willReturn(List.of(target));
        given(handoverService.findExpiredUnresolvedAcceptanceIds(any())).willReturn(List.of());
        given(handoverService.findOldCancelScheduleUnconfirmedIds()).willReturn(List.of());

        service().runPayerHandoverNightlyReconcile();

        verify(handoverService).expireOverdueUnaccepted(target);
    }

    @Test
    @DisplayName("AC-34: 夜次照合バッチは old_cancel_scheduled_at 未確認の行を Stripe と突合する")
    void nightlyReconcile_reconcilesUnconfirmedCancelSchedule() {
        UUID target = UUID.randomUUID();
        givenNoStalledSwitching();
        given(handoverService.findOverdueUnacceptedIds(any())).willReturn(List.of());
        given(handoverService.findExpiredUnresolvedAcceptanceIds(any())).willReturn(List.of());
        given(handoverService.findOldCancelScheduleUnconfirmedIds()).willReturn(List.of(target));

        service().runPayerHandoverNightlyReconcile();

        verify(handoverService).reconcileOldCancelSchedule(target);
    }

    @Test
    @DisplayName("§5.3: 夜次照合バッチは期限超過のまま未解決の承諾も照合する")
    void nightlyReconcile_reconcilesExpiredAcceptance() {
        UUID target = UUID.randomUUID();
        givenNoStalledSwitching();
        given(handoverService.findOverdueUnacceptedIds(any())).willReturn(List.of());
        given(handoverService.findExpiredUnresolvedAcceptanceIds(any())).willReturn(List.of(target));
        given(handoverService.findOldCancelScheduleUnconfirmedIds()).willReturn(List.of());

        service().runPayerHandoverNightlyReconcile();

        verify(handoverService).reconcileExpiredAcceptance(target);
    }

    @Test
    @DisplayName("夜次照合: 期限超過照合が1件落ちても cancel_at_period_end の照合は実行される")
    void nightlyReconcile_expiredFailureDoesNotSkipCancelReconcile() {
        UUID expired = UUID.randomUUID();
        UUID unconfirmed = UUID.randomUUID();
        givenNoStalledSwitching();
        given(handoverService.findOverdueUnacceptedIds(any())).willReturn(List.of());
        given(handoverService.findExpiredUnresolvedAcceptanceIds(any())).willReturn(List.of(expired));
        given(handoverService.findOldCancelScheduleUnconfirmedIds()).willReturn(List.of(unconfirmed));
        willThrow(new IllegalStateException("boom")).given(handoverService).reconcileExpiredAcceptance(expired);

        service().runPayerHandoverNightlyReconcile();

        verify(handoverService).reconcileOldCancelSchedule(unconfirmed);
    }

    @Test
    @DisplayName("P1-5: 滞留照合はキーセットで【前へ進む】"
            + "——毎回同じ先頭を舐めると、状態が変わらない正常待機行だけで後続が飢餓する")
    void stalledSwitching_advancesByKeyset() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant firstCursor = Instant.parse("2026-09-01T00:00:00Z");
        Instant secondCursor = Instant.parse("2026-09-02T00:00:00Z");
        given(handoverService.findOverdueUnacceptedIds(any())).willReturn(List.of());
        given(handoverService.findExpiredUnresolvedAcceptanceIds(any())).willReturn(List.of());
        given(handoverService.findOldCancelScheduleUnconfirmedIds()).willReturn(List.of());
        // 1ページ目は cursor=EPOCH、2ページ目は【1ページ目の最後の acceptedAt】で呼ばれること。
        given(handoverService.findStalledSwitchingPage(Instant.EPOCH, 200))
                .willReturn(new BillingPayerHandoverService.StalledSwitchingPage(
                        List.of(first), firstCursor));
        given(handoverService.findStalledSwitchingPage(firstCursor, 200))
                .willReturn(new BillingPayerHandoverService.StalledSwitchingPage(
                        List.of(second), secondCursor));
        given(handoverService.findStalledSwitchingPage(secondCursor, 200))
                .willReturn(new BillingPayerHandoverService.StalledSwitchingPage(List.of(), null));

        service().runPayerHandoverNightlyReconcile();

        // 先頭を舐め直すのではなく、2ページ目の行まで到達している。
        verify(handoverService).reconcileStalledSwitching(first);
        verify(handoverService).reconcileStalledSwitching(second);
    }

    @Test
    @DisplayName("対象0件なら1件も解決を呼ばない（空振りで Stripe を叩かない）")
    void emptyTargets_noWork() {
        given(handoverService.findSwitchDueHandoverIds(any())).willReturn(List.of());

        service().runPayerHandoverSwitch();

        verify(handoverService, never()).executeSwitch(any());
    }
}
