package com.mannschaft.app.auth.guardianship;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F08.9 P3c-3 自立移行通知の送信記録リポジトリ（重複送信防止）。
 *
 * <p>進学予告／封印時未設定メールの 2 バッチが、同一（受信者×子×封印境界日×種別）で
 * 既に送信済みかを判定し、未送信のものだけ送る。PK は UUIDv7（BINARY(16)）。</p>
 */
public interface GuardianshipTransitionNotificationRepository
        extends JpaRepository<GuardianshipTransitionNotificationEntity, UUID> {

    /**
     * 指定の（種別×宛先×子×封印境界日）で送信記録が既に存在するかを判定する。
     *
     * @param notificationKind 通知種別
     * @param recipientUserId  宛先ユーザーID（進学予告＝保護者／封印時メール＝子本人）
     * @param childUserId      対象の子ユーザーID
     * @param sealDate         封印境界日
     * @return 既送信なら true
     */
    boolean existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
            GuardianshipTransitionNotificationKind notificationKind,
            Long recipientUserId,
            Long childUserId,
            LocalDate sealDate);
}
