package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.ConfirmedVia;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F04.9 確認通知受信者レスポンスDTO。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationRecipientResponse {

    private Long id;

    /** 受信者ユーザーID */
    private Long userId;

    /** 確認済みフラグ */
    private Boolean isConfirmed;

    /** 確認日時（未確認の場合 NULL） */
    private LocalDateTime confirmedAt;

    /** 確認経路（APP / TOKEN / BULK）（未確認の場合 NULL） */
    private ConfirmedVia confirmedVia;

    /** 除外（確認免除）日時（NULL の場合は除外されていない） */
    private LocalDateTime excludedAt;

    private LocalDateTime createdAt;
}
