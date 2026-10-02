package com.mannschaft.app.school.event;

import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;

/**
 * 出席要件評価のステータス変化イベント（Issue #2990 L6 TX_NOTIFY_BARE 是正）。
 *
 * <p>{@code AttendanceRequirementBatchService#runDailyEvaluation} は業務トランザクション
 * （＝バッチ全体を覆う単一 {@code @Transactional}）の内側では本イベントを publish するだけに留め、
 * 教員への警告通知は {@code SchoolAttendanceNotificationListener}（{@code AFTER_COMMIT}）が行う。</p>
 *
 * <p>規程名（{@code rule.getName()}）と通知先教員IDは {@code ruleId} から読み直せるため積まない。</p>
 *
 * @param studentUserId 対象生徒のユーザーID
 * @param ruleId        適用した出席要件規程のID
 * @param newStatus     新しい評価ステータス
 */
public record AttendanceRequirementStatusChangedEvent(
        Long studentUserId,
        Long ruleId,
        EvaluationStatus newStatus) {
}
