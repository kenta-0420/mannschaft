package com.mannschaft.app.reservation.event;

import java.time.LocalDateTime;

/**
 * 臨時休業未確認リマインドの通知配送要求イベント（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>{@code EmergencyClosureReminderRunner} が 1 件ぶんのリマインド送信済み記録を独立トランザクションで
 * コミットする直前に publish し、{@link EmergencyClosureReminderNotificationListener} が
 * {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <h2>受信者情報をイベントに載せる理由</h2>
 * <p>受信者の氏名・メール・locale は {@code users} を引く越境参照であり、reservation ドメイン側の
 * バッチが対象抽出時に一括取得（N+1 対策）している。配送リスナー側で解決し直すと、
 * リスナーが auth ドメインの {@code UserRepository} を直接 DI することになり
 * アーキテクチャ番人（D-5 / D-1）に触れるうえ、同じ行を二度引くことになる。</p>
 *
 * @param phase           リマインドの段階
 * @param confirmationId  確認行ID
 * @param closureId       臨時休業ID（通知の source）
 * @param teamId          チームID（通知スコープ）
 * @param subject         臨時休業の件名
 * @param reason          臨時休業の理由
 * @param messageBody     臨時休業の本文（患者宛メールにのみ使用）
 * @param appointmentAt   予約日時
 * @param patientUserId   患者ユーザーID（メールの冪等キーに使用）
 * @param patientName     患者氏名（送信者宛アラートの文面にのみ使用）
 * @param recipientUserId 受信者ユーザーID
 * @param recipientEmail  受信者メールアドレス
 * @param recipientLocale 受信者 locale（BCP 47 タグ。{@code null} なら {@code ja}）
 * @param actorId         通知の実行者ID（患者宛は送信者、送信者宛は {@code null}）
 */
public record EmergencyClosureReminderNotificationEvent(
        Phase phase,
        Long confirmationId,
        Long closureId,
        Long teamId,
        String subject,
        String reason,
        String messageBody,
        LocalDateTime appointmentAt,
        Long patientUserId,
        String patientName,
        Long recipientUserId,
        String recipientEmail,
        String recipientLocale,
        Long actorId) {

    /** リマインドの段階。 */
    public enum Phase {
        /** 予約 3 時間前 — 未確認の患者本人へ再リマインド。 */
        PATIENT,
        /** 予約 2 時間前 — まだ未確認なら送信者（院長等）へアラート。 */
        OPERATOR
    }
}
