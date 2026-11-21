package com.mannschaft.app.shiftbudget.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 並行試練（AC44・45・56・61・67・72・78）が処理の順序を固定するための試験用フック（CMP-260909-2152）。
 *
 * <p>本番では何もしない。実装は各段の所定の位置で必ず呼ぶこと（試練が spy で待ち合わせ・失敗注入を行う）。
 * 呼ぶ位置は各メソッドの Javadoc のとおり。</p>
 */
@Component
public class ShiftBudgetLockStepHooks {

    /** Prepare TX 内。割当 FOR UPDATE・イベント PREPARED INSERT・印付けの後、コミット前（割当ロック保持中）。 */
    public void afterPrepareMarked(Long allocationId, UUID closeEventId) {
        // 本番では何もしない
    }

    /** 段取り役。Prepare コミット後、Post 呼出前。 */
    public void afterPrepared(Long allocationId, UUID closeEventId) {
        // 本番では何もしない
    }

    /** 段取り役。Post の判定（POSTED／ABORTED）受領後、Commit／Abort 呼出前。 */
    public void afterPosted(Long allocationId, UUID closeEventId, String outcome) {
        // 本番では何もしない
    }

    /** 記録 TX 内。手順(1) 発見（通常 SELECT）の後、手順(2) 割当 FOR UPDATE の前。 */
    public void afterRecordDiscovered(Long shiftId, Long slotId, Long userId) {
        // 本番では何もしない
    }

    /** 取消 TX 内。スケジュール行を FOR SHARE で current read した直後（FOR SHARE 保持中）。 */
    public void afterCancelScheduleRead(Long shiftId, String scheduleStatus) {
        // 本番では何もしない
    }

    /** 遅延再実行。現在のスケジュール状態から取消側を選んだ後、取消 TX を開始する前。 */
    public void beforeReplayCancelTx(UUID deferralId, Long shiftId) {
        // 本番では何もしない
    }

    /** 遅延再実行。確保（claimed_by／lease_until の UPDATE）コミット後、処理開始前。 */
    public void afterDeferralClaimed(UUID deferralId, String owner) {
        // 本番では何もしない
    }

    /** 段取り役。期間行の確定（ensureFixed）を呼ぶ直前（終了判定の後。締め日変更の割り込みを試験が差し込む位置）。 */
    public void beforeEnsureFixed(Long organizationId, java.time.YearMonth periodKey) {
        // 本番では何もしない
    }
}
