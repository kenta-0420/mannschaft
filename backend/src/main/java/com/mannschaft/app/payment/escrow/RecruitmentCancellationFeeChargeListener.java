package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.recruitment.event.RecruitmentCancellationFeeChargeRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

/**
 * F03.11.1 募集キャンセル料の徴収を起動するリスナ（設計書 §3.3 ステップ 2・§12-5）。
 *
 * <p>recruitment ドメインの {@link RecruitmentCancellationFeeChargeRequestedEvent} を受け、
 * {@link ConnectChargeService#settleCancellationFee} を呼ぶ。成否に応じて
 * {@code RecruitmentCancellationFeeChargedEvent} / {@code ...FailedEvent} を発火し、
 * 記録の状態更新は recruitment 側のリスナに委ねる（クロスドメイン直接呼び出しをしない・§3.1-3）。</p>
 *
 * <p>キャンセルそのものは利用者の意思表示であり、徴収の失敗で巻き戻してはならない（§3.1-2）。
 * そのためこのリスナはキャンセルのトランザクションがコミットされた後にのみ動く必要がある。</p>
 *
 * <p><b>第三陣（試練）時点の状態</b>: 受け入れ条件 AC-4 の red テストが参照するための宣言のみを置いている。
 * イベント購読の結線と本体は第四陣（出陣）で実装する。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RecruitmentCancellationFeeChargeListener {

    private final ConnectChargeService connectChargeService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * キャンセル料の徴収要求を受けて徴収を実行する。
     *
     * @param event 徴収要求イベント
     */
    public void onCancellationFeeChargeRequested(RecruitmentCancellationFeeChargeRequestedEvent event) {
        throw new UnsupportedOperationException("F03.11.1 徴収リスナは第四陣で実装");
    }
}
