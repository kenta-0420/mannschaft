package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F04.9 確認通知作成リクエストDTO。
 */
@Getter
@NoArgsConstructor
public class ConfirmableNotificationCreateRequest {

    /** 通知タイトル（必須） */
    @NotBlank
    private String title;

    /** 通知本文（任意） */
    private String body;

    /** 優先度（省略時はNORMAL） */
    private ConfirmableNotificationPriority priority = ConfirmableNotificationPriority.NORMAL;

    /** 確認期限（任意。NULLは無期限） */
    private LocalDateTime deadlineAt;

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
}
