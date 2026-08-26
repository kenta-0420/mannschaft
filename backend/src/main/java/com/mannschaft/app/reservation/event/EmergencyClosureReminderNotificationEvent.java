package com.mannschaft.app.reservation.event;

/**
 * 臨時休業未確認リマインドの通知配送要求イベント（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>{@code EmergencyClosureReminderRunner} が 1 件ぶんのリマインド送信済み記録を独立トランザクションで
 * コミットする直前に publish し、{@link EmergencyClosureReminderNotificationListener} が
 * {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <h2>イベントに載せる値の方針（検分是正）</h2>
 * <p>確定設計の原則「イベントには ID と種別だけを載せる」に従い、<b>配送側で読み直せる値は載せない</b>。
 * 予約日時・件名・理由・本文・チームID・実行者IDは
 * {@code emergency_closure_confirmations} / {@code emergency_closures} を
 * {@code confirmationId} / {@code closureId} で読み直して得る（本バッチはどちらの行も削除も
 * 論理削除もしないため、コミット後でも必ず生存している）。</p>
 *
 * <p>逆に<b>読み直せない値だけを残す</b>。受信者の氏名・メール・locale は {@code users} を引く越境参照で、
 * reservation ドメインの配送リスナーからは解決できない（{@code auth} の {@code UserRepository} を
 * 直接 DI するとアーキテクチャ番人 D-3 / D-5 に触れる）。バッチが対象抽出時に一括取得（N+1 対策）した
 * 値をそのまま渡す。</p>
 *
 * @param phase           リマインドの段階
 * @param confirmationId  確認行ID（予約日時・患者ユーザーIDの読み直し元）
 * @param closureId       臨時休業ID（通知の source。件名・理由・本文・チームID・実行者IDの読み直し元）
 * @param patientName     患者氏名（送信者宛アラートの文面にのみ使用。{@code users} 由来で読み直せない）
 * @param recipientUserId 受信者ユーザーID
 * @param recipientEmail  受信者メールアドレス
 * @param recipientLocale 受信者 locale（BCP 47 タグ。{@code null} なら {@code ja}）
 */
public record EmergencyClosureReminderNotificationEvent(
        Phase phase,
        Long confirmationId,
        Long closureId,
        String patientName,
        Long recipientUserId,
        String recipientEmail,
        String recipientLocale) {

    /** リマインドの段階。 */
    public enum Phase {
        /** 予約 3 時間前 — 未確認の患者本人へ再リマインド。 */
        PATIENT,
        /** 予約 2 時間前 — まだ未確認なら送信者（院長等）へアラート。 */
        OPERATOR
    }
}
