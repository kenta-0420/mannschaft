package com.mannschaft.app.shiftbudget.service;

import java.util.List;

/**
 * シフト公開の同期記録（{@code ShiftBudgetConsumptionRecordListener#onShiftPublished}）の結果集計（CMP-260909-2152 確定設計 r12 §4.2）。
 *
 * <p>遅延再実行（{@link ShiftBudgetRecordDeferralService#replay}）が、REPLAYED／REDEFERRED／PENDING（失敗）を決めるために使う。</p>
 *
 * @param recorded      記録した (slot, user) 数
 * @param skippedNormal 正常スキップ（締め済み・計上済み・非公開・時給未設定・割当不在など）
 * @param deferred      締めの PREPARED を理由に遅延へ回した数
 * @param failed        失敗した数
 * @param failures      失敗した (slot, user)
 */
public record ShiftRecordOutcome(int recorded, int skippedNormal, int deferred, int failed, List<SlotUser> failures) {

    /** 失敗した (slot, user)。 */
    public record SlotUser(Long slotId, Long userId) {
    }

    /** 何も起きなかった結果。 */
    public static ShiftRecordOutcome empty() {
        return new ShiftRecordOutcome(0, 0, 0, 0, List.of());
    }
}
