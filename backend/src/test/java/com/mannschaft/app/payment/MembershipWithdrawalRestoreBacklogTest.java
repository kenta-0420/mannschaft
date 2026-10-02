package com.mannschaft.app.payment;

import com.mannschaft.app.auth.service.WithdrawalStateQueryService;
import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationEntity;
import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationStatus;
import com.mannschaft.app.payment.repository.MembershipPayerWithdrawalCancellationRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 柱③-B PR-4（CMP-260901-1538）Codex検分1巡目 P1-1 の回帰テスト:
 * <b>退会取消イベントを取りこぼした払い手</b>を解除側へ振り分けられること。
 *
 * <h2>塞ぐ穴</h2>
 * <p>是正前の解除側バッチは {@code RESTORING} だけを抽出していた。しかし退会取消イベントが
 * {@code prepareRestore} に到達する前に失われると（{@code event-pool} の投入拒否・
 * commit 直後のプロセス停止）、作業行は {@code PENDING} / {@code FAILED} / {@code SUCCEEDED}
 * のまま残る。解約側へ回しても「いまは退会申請中でない」ため {@code reserveAll} が空を返すだけで
 * 行も終端化されず、<b>退会を取り消した利用者のメンバーシップが期末で終了する</b>。</p>
 *
 * <h2>判定の正本</h2>
 * <p>振り分けは<b>状態や時刻から退会世代を推測せず</b>、auth ドメインの native 経路
 * （{@code users.deleted_at} / {@code withdrawal_attempt_id}）が返す「いま退会申請中の払い手」
 * との差集合で行う。この戦役では世代の推測が3度破れている。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("払い手退会取消の取りこぼし振り分け（PR-4 P1-1）")
class MembershipWithdrawalRestoreBacklogTest {

    @Mock private MembershipPayerWithdrawalCancellationRepository payerWithdrawalCancellationRepository;
    @Mock private WithdrawalStateQueryService withdrawalStateQueryService;

    @InjectMocks private MembershipSubscriptionService service;

    /** 本番の抽出と同じ引数（ここを {@code anyList()} にすると2つの走査を区別できず本番の振り分けを迂回する）。 */
    private static final List<MembershipPayerWithdrawalCancellationStatus> RESTORING_ONLY =
            List.of(MembershipPayerWithdrawalCancellationStatus.RESTORING);

    private static final List<MembershipPayerWithdrawalCancellationStatus> BACKLOG_STATUSES =
            List.of(MembershipPayerWithdrawalCancellationStatus.PENDING,
                    MembershipPayerWithdrawalCancellationStatus.FAILED,
                    MembershipPayerWithdrawalCancellationStatus.RESTORING,
                    MembershipPayerWithdrawalCancellationStatus.SUCCEEDED);

    private MembershipPayerWithdrawalCancellationEntity row(
            Long payerUserId, MembershipPayerWithdrawalCancellationStatus status) {
        MembershipPayerWithdrawalCancellationEntity e =
                MembershipPayerWithdrawalCancellationEntity.builder()
                        .subscriptionId(UUID.randomUUID())
                        .payerUserId(payerUserId)
                        .status(status)
                        .build();
        return e;
    }

    @Test
    @DisplayName("退会申請中でない払い手の SUCCEEDED / FAILED / PENDING も解除側へ振り分ける"
            + "（RESTORING だけを見ると取消イベントの取りこぼしを永久に拾えない）")
    void includesPayersWhoAreNoLongerWithdrawing() {
        // 1L: 退会を取り消したのに SUCCEEDED（予約成立）のまま残っている＝解除されるべき
        // 2L: いまも退会申請中＝解除してはならない
        // RESTORING 単独の抽出は空（取消イベント自体が失われた想定）。
        given(payerWithdrawalCancellationRepository.findByStatusInOrderByUpdatedAtAsc(
                RESTORING_ONLY)).willReturn(List.of());
        given(payerWithdrawalCancellationRepository.findByStatusInOrderByUpdatedAtAsc(
                BACKLOG_STATUSES)).willReturn(List.of(
                        row(1L, MembershipPayerWithdrawalCancellationStatus.SUCCEEDED),
                        row(2L, MembershipPayerWithdrawalCancellationStatus.PENDING)));
        given(withdrawalStateQueryService.findUserIdsWithPendingWithdrawal())
                .willReturn(List.of(2L));

        List<Long> targets = service.findWithdrawalRestoreRetryPayerUserIds();

        assertThat(targets).contains(1L);
        assertThat(targets).doesNotContain(2L);
    }

    @Test
    @DisplayName("全員がいまも退会申請中なら解除側の対象は無い（誤って予約を解除しない）")
    void excludesEveryoneStillWithdrawing() {
        given(payerWithdrawalCancellationRepository.findByStatusInOrderByUpdatedAtAsc(
                RESTORING_ONLY)).willReturn(List.of());
        given(payerWithdrawalCancellationRepository.findByStatusInOrderByUpdatedAtAsc(
                BACKLOG_STATUSES)).willReturn(List.of(
                        row(1L, MembershipPayerWithdrawalCancellationStatus.SUCCEEDED),
                        row(2L, MembershipPayerWithdrawalCancellationStatus.FAILED)));
        given(withdrawalStateQueryService.findUserIdsWithPendingWithdrawal())
                .willReturn(List.of(1L, 2L));

        assertThat(service.findWithdrawalRestoreRetryPayerUserIds()).isEmpty();
    }

    @Test
    @DisplayName("復旧しうる行が1件も無ければ退会状態の照会自体を行わない（無駄な全件走査をしない）")
    void noCandidates_noWithdrawalStateQuery() {
        given(payerWithdrawalCancellationRepository.findByStatusInOrderByUpdatedAtAsc(
                RESTORING_ONLY)).willReturn(List.of());
        given(payerWithdrawalCancellationRepository.findByStatusInOrderByUpdatedAtAsc(
                BACKLOG_STATUSES)).willReturn(List.of());

        assertThat(service.findWithdrawalRestoreRetryPayerUserIds()).isEmpty();
        org.mockito.Mockito.verify(withdrawalStateQueryService, org.mockito.Mockito.never())
                .findUserIdsWithPendingWithdrawal();
    }
}
