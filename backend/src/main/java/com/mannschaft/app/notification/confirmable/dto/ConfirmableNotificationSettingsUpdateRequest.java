package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F04.9 確認通知設定更新リクエストDTO。
 */
@Getter
@NoArgsConstructor
public class ConfirmableNotificationSettingsUpdateRequest {

    /** デフォルト1回目リマインド送信タイミング（分）。NULLでリマインドなし */
    private Integer defaultFirstReminderMinutes;

    /** デフォルト2回目リマインド送信タイミング（分）。NULLでリマインドなし */
    private Integer defaultSecondReminderMinutes;

    /** 送信者へのアラート閾値（確認率%）。NULLの場合は80%（デフォルト） */
    private Integer senderAlertThresholdPercent;

    /**
     * デフォルト未確認者リスト公開範囲。
     * NULL の場合は CREATOR_AND_ADMIN（既存挙動）を採用する。
     */
    private UnconfirmedVisibility defaultUnconfirmedVisibility;
}
