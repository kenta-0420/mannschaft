package com.mannschaft.app.survey.event;

import java.time.Instant;
import java.util.List;

/**
 * アンケートの督促（手動リマインド）・締切延長の付随通知の配送要求イベント
 * （Issue #2997 / CMP-260827-1152 G5）。
 *
 * <p>{@code SurveyRemindService#remind} / {@code SurveyService#extendDeadline} の業務トランザクション内で
 * 受信者を確定してスナップショットとして載せ、{@link com.mannschaft.app.survey.listener.SurveyReminderNotificationListener}
 * が {@code AFTER_COMMIT} で受け取って事前認可バルク経路で配信する。業務TXがロールバックすれば通知は作られず、
 * 通知の失敗が督促カウンタ・締切の確定を巻き戻さない。</p>
 *
 * @param kind             通知の種別
 * @param surveyId         アンケートID（通知の sourceId）
 * @param scopeType        スコープ種別（{@code TEAM} / {@code ORGANIZATION}）
 * @param scopeId          スコープID
 * @param title            アンケート名（利用者が書いた文字列）
 * @param recipientUserIds 受信者（配信母集団で事前認可済み・業務TX内で確定済み）
 * @param actorId          実行者ID
 * @param newDeadline      新しい締切（{@link Kind#DEADLINE_EXTENDED} のみ。それ以外は null）。
 *                         元データ（{@code surveys.expires_at}）は JVM 既定ゾーン（JST 固定）の壁時計 {@code LocalDateTime}。
 *                         イベントには瞬間（{@link Instant}）として載せ、リスナーが同じ既定ゾーンで壁時計へ戻して文面にする
 *                         （方針: docs/architecture/datetime_policy_utc_instant_vs_wallclock.md）
 */
public record SurveyReminderNotificationEvent(
        Kind kind,
        Long surveyId,
        String scopeType,
        Long scopeId,
        String title,
        List<Long> recipientUserIds,
        Long actorId,
        Instant newDeadline) {

    /** 通知の種別。 */
    public enum Kind {
        /** 手動督促（未回答者へ）。 */
        REMIND,
        /** 締切延長（配信母集団へ）。 */
        DEADLINE_EXTENDED
    }

    public SurveyReminderNotificationEvent {
        recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
    }
}
