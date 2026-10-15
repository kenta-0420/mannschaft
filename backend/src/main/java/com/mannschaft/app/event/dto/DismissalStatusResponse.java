package com.mannschaft.app.event.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 解散通知の送信状態レスポンス DTO。F03.12 §16。
 *
 * <p>GET /dismissal/status の返却値。解散通知の送信済み有無・リマインド回数を含む。</p>
 */
@Getter
@RequiredArgsConstructor
@Builder
public class DismissalStatusResponse {

    /**
     * 解散通知が送信された日時。null の場合は未送信。
     */
    private final LocalDateTime dismissalNotificationSentAt;

    /**
     * 解散通知を送信したユーザーのID。未送信の場合は null。
     */
    private final Long dismissalNotifiedByUserId;

    /**
     * 主催者へのリマインド送信回数（0〜3）。
     */
    private final int reminderCount;

    /**
     * 最終リマインド送信日時。1度もリマインドされていない場合は null。
     */
    private final LocalDateTime lastReminderAt;

    /**
     * 解散通知送信済みかどうか。dismissalNotificationSentAt が非 null の場合 true。
     */
    private final boolean dismissed;
}
