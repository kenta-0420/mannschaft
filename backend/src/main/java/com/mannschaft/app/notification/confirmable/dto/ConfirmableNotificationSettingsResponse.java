package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F04.9 確認通知設定レスポンスDTO。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationSettingsResponse {

    /** デフォルト1回目リマインド送信タイミング（分）。NULLはリマインドなし */
    private Integer defaultFirstReminderMinutes;

    /** デフォルト2回目リマインド送信タイミング（分）。NULLはリマインドなし */
    private Integer defaultSecondReminderMinutes;

    /** 送信者へのアラート閾値（確認率%） */
    private Integer senderAlertThresholdPercent;

    /**
     * デフォルト未確認者リスト公開範囲。
     * 通知作成時にリクエストで省略された場合の既定値。
     */
    private UnconfirmedVisibility defaultUnconfirmedVisibility;
}
