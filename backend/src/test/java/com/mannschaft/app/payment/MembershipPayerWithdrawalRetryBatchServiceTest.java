package com.mannschaft.app.payment;

import com.mannschaft.app.payment.service.MembershipPayerWithdrawalRetryBatchService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.willThrow;

/**
 * 柱③-B PR-4（CMP-260901-1538）: 払い手退会に伴う期末解約の<b>再試行バッチ駆動</b>の単体テスト（試練先行）。
 *
 * <p>PR-3 は状態（{@code membership_payer_withdrawal_cancellations} の非終端3値）と
 * {@code findWithdrawalCancelBacklog()} を用意したが<b>拾う主体が居なかった</b>ため、
 * 失敗した期末解約は自動では二度と再試行されなかった（PR-3 の「リリース依存」の実体）。
 * 本バッチがその駆動主体である。</p>
 *
 * <h2>重複排除（PR-3 からの申し送り）</h2>
 * <p>PR-3 の Javadoc は2経路（非終端の作業行 / backlog）が「定義上互いに素」としているが、
 * {@code findWithdrawalCancelBacklog()} は複数の独立クエリの組み合わせでトランザクションを張っておらず、
 * <b>照会の間に作業行が作られれば重なる</b>——時間軸を含めると互いに素ではない。
 * よって本バッチは<b>払い手単位で union/dedup</b> してから駆動し、同じ払い手を二度走らせない。
 * さらに払い手ごとの処理自体が行ロック下で状態を取り直して再検証するため、
 * 仮に重複投入されても二重発行にはならない（二段の防御）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MembershipPayerWithdrawalRetryBatchService 単体テスト（期末解約の再試行駆動）")
class MembershipPayerWithdrawalRetryBatchServiceTest {

    @Mock private MembershipSubscriptionService membershipSubscriptionService;

    @InjectMocks private MembershipPayerWithdrawalRetryBatchService batchService;

    @Test
    @DisplayName("非終端の作業行と backlog の両経路から拾い、払い手単位で解約を再試行する")
    void retriesBothWorkRowAndBacklogPayers() {
        given(membershipSubscriptionService.findWithdrawalCancelRetryPayerUserIds())
                .willReturn(List.of(1L, 2L));
        given(membershipSubscriptionService.findWithdrawalRestoreRetryPayerUserIds())
                .willReturn(List.of());

        batchService.runWithdrawalCancelRetry();

        verify(membershipSubscriptionService).cancelAllForPayerOnWithdrawal(1L);
        verify(membershipSubscriptionService).cancelAllForPayerOnWithdrawal(2L);
    }

    @Test
    @DisplayName("同じ払い手が両経路に現れても解約再試行は1回だけ走る（union/dedup）")
    void deduplicatesPayerAcrossBothSources() {
        // 抽出側が dedup 済みの集合を返す契約であることを、駆動側でも二重呼び出ししないことで固定する。
        given(membershipSubscriptionService.findWithdrawalCancelRetryPayerUserIds())
                .willReturn(List.of(1L, 1L, 2L));
        given(membershipSubscriptionService.findWithdrawalRestoreRetryPayerUserIds())
                .willReturn(List.of());

        batchService.runWithdrawalCancelRetry();

        verify(membershipSubscriptionService, times(1)).cancelAllForPayerOnWithdrawal(1L);
        verify(membershipSubscriptionService, times(1)).cancelAllForPayerOnWithdrawal(2L);
    }

    @Test
    @DisplayName("RESTORING で止まった行は解除側として再試行する（解約側へ混ぜない）")
    void restoringRowsAreDrivenAsRestore() {
        given(membershipSubscriptionService.findWithdrawalCancelRetryPayerUserIds())
                .willReturn(List.of());
        given(membershipSubscriptionService.findWithdrawalRestoreRetryPayerUserIds())
                .willReturn(List.of(5L));

        batchService.runWithdrawalCancelRetry();

        verify(membershipSubscriptionService).restoreAllForPayerOnWithdrawalCancelled(5L);
        verify(membershipSubscriptionService, never()).cancelAllForPayerOnWithdrawal(5L);
    }

    @Test
    @DisplayName("1人の払い手の失敗が他の払い手の再試行を止めない")
    void oneFailureDoesNotStopOthers() {
        given(membershipSubscriptionService.findWithdrawalCancelRetryPayerUserIds())
                .willReturn(List.of(1L, 2L));
        given(membershipSubscriptionService.findWithdrawalRestoreRetryPayerUserIds())
                .willReturn(List.of());
        willThrow(new IllegalStateException("boom"))
                .given(membershipSubscriptionService).cancelAllForPayerOnWithdrawal(1L);

        batchService.runWithdrawalCancelRetry();

        verify(membershipSubscriptionService).cancelAllForPayerOnWithdrawal(2L);
    }

    @Test
    @DisplayName("解約側が全滅しても解除側は実行される（片方の障害が他方を巻き込まない）")
    void restorePassRunsEvenIfCancelPassFails() {
        given(membershipSubscriptionService.findWithdrawalCancelRetryPayerUserIds())
                .willReturn(List.of(1L));
        given(membershipSubscriptionService.findWithdrawalRestoreRetryPayerUserIds())
                .willReturn(List.of(5L));
        willThrow(new IllegalStateException("boom"))
                .given(membershipSubscriptionService).cancelAllForPayerOnWithdrawal(1L);

        batchService.runWithdrawalCancelRetry();

        verify(membershipSubscriptionService).restoreAllForPayerOnWithdrawalCancelled(5L);
    }
}
