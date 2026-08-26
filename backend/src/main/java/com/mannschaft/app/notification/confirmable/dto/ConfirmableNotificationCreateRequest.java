package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * F04.9 確認通知作成リクエストDTO。
 */
@Getter
@NoArgsConstructor
public class ConfirmableNotificationCreateRequest {

    private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    /** 通知タイトル（必須） */
    @NotBlank
    private String title;

    /** 通知本文（任意） */
    private String body;

    /** 優先度（省略時はNORMAL） */
    private ConfirmableNotificationPriority priority = ConfirmableNotificationPriority.NORMAL;

    /**
     * 確認期限（任意。NULLは無期限）。
     *
     * <p>フロントエンドはユーザーのローカル時刻をオフセット付きで送信すること（例: {@code 2026-06-10T15:00:00+09:00}）。
     * サービス層では {@link #getDeadlineAtAsJst()} を使って JST の {@link LocalDateTime} に変換してから
     * Entity に保存する。</p>
     */
    private OffsetDateTime deadlineAt;

    /** 1回目リマインド送信タイミング（分）。NULLの場合はスコープ設定から継承 */
    private Integer firstReminderMinutes;

    /** 2回目リマインド送信タイミング（分）。NULLの場合はスコープ設定から継承 */
    private Integer secondReminderMinutes;

    /** 確認ボタン遷移先URL（任意） */
    private String actionUrl;

    /** 使用テンプレートID（任意） */
    private Long templateId;

    /**
     * 未確認者リストの公開範囲（任意）。
     *
     * <p>NULL の場合はスコープ設定（{@code default_unconfirmed_visibility}）を採用する。
     * スコープ設定もデフォルト（CREATOR_AND_ADMIN）。</p>
     */
    private UnconfirmedVisibility unconfirmedVisibility;

    /** 受信者ユーザーIDリスト（必須・最低1件） */
    @NotEmpty
    private List<Long> recipientUserIds;

    /**
     * 確認期限を JST の {@link LocalDateTime} に変換して返す。
     *
     * <p>{@code deadlineAt} が NULL の場合は NULL を返す（無期限）。
     * フロントエンドから任意のオフセット付き日時を受け取り、JST ゾーンで保持している
     * {@code confirmable_notifications.deadline_at}（DATETIME 型）に合わせて変換する。</p>
     *
     * @return JST 換算の {@link LocalDateTime}、または NULL
     */
    public LocalDateTime getDeadlineAtAsJst() {
        if (deadlineAt == null) {
            return null;
        }
        return deadlineAt.atZoneSameInstant(ZONE_JST).toLocalDateTime();
    }
}
