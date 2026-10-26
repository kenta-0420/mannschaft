package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * フレンドチーム通知送信レスポンス（202 Accepted）。
 */
@Getter
@Builder
public class FriendNotificationDeliveryResponse {

    /** 配信ジョブ ID（"frdl_" プレフィックス + yyyyMMddHHmmss + ランダム4桁）*/
    private final String deliveryId;

    /** キューに積まれた送信先チーム数 */
    private final int queuedTeamsCount;

    /** キューに積まれた送信先管理者ユーザー数 */
    private final int queuedAdminsCount;

    /** キューに積まれた日時 */
    private final LocalDateTime queuedAt;
}
