package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * F04.9 確認通知詳細レスポンスDTO。
 *
 * <p>一覧用 {@link ConfirmableNotificationResponse} の全フィールドに加えて
 * 本文・URL・リマインド設定・タイムスタンプ詳細を含む。</p>
 *
 * <p>confirmedCount は MapStruct 変換後に Controller 側でセットする。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationDetailResponse {

    private Long id;
    private ScopeType scopeType;
    private Long scopeId;
    private String title;

    /** 通知本文（任意） */
    private String body;

    private ConfirmableNotificationPriority priority;
    private ConfirmableNotificationStatus status;
    private LocalDateTime deadlineAt;

    /** 1回目リマインド送信タイミング（分） */
    private Integer firstReminderMinutes;

    /** 2回目リマインド送信タイミング（分） */
    private Integer secondReminderMinutes;

    /** 確認ボタン遷移先URL */
    private String actionUrl;

    /** 送信時点の受信者総数 */
    private Integer totalRecipientCount;

    /** 確認済み受信者数 */
    private Long confirmedCount;

    /** 未確認者リストの公開範囲（HIDDEN / CREATOR_AND_ADMIN / ALL_MEMBERS） */
    private UnconfirmedVisibility unconfirmedVisibility;

    /** キャンセル日時 */
    private LocalDateTime cancelledAt;

    /** 完了日時 */
    private LocalDateTime completedAt;

    /** 期限切れ日時 */
    private LocalDateTime expiredAt;

    /** 作成者ユーザーID */
    private Long createdBy;

    private LocalDateTime createdAt;
}
