package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargeFailedEvent;
import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargedEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * F03.11.1 徴収結果をキャンセル記録へ反映するリスナ（設計書 §3.3 ステップ 6・§12-6）。
 *
 * <p>payment ドメインから返ってきた成否のイベントを受け、記録を {@code markPaid(paymentId)} または
 * {@code markFailed()} へ移す。トランザクションは自ドメイン内に閉じた短命なものとする。</p>
 *
 * <p><b>第三陣（試練）時点の状態</b>: 宣言のみ。本体は第四陣（出陣）で実装する。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RecruitmentCancellationFeeResultListener {

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;

    /**
     * 徴収成功を受けて記録を PAID にする。
     *
     * @param event 徴収成功イベント
     */
    public void onCancellationFeeCharged(RecruitmentCancellationFeeChargedEvent event) {
        throw new UnsupportedOperationException("F03.11.1 徴収結果リスナは第四陣で実装");
    }

    /**
     * 徴収失敗を受けて記録を FAILED にする。
     *
     * @param event 徴収失敗イベント
     */
    public void onCancellationFeeChargeFailed(RecruitmentCancellationFeeChargeFailedEvent event) {
        throw new UnsupportedOperationException("F03.11.1 徴収結果リスナは第四陣で実装");
    }
}
