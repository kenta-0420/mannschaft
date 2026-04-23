package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.ConfirmedVia;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F04.9 確認通知受信者レスポンスDTO。
 *
 * <p><b>F04.9 Phase D 公開範囲対応</b>:
 * MEMBER 視点（unconfirmed_visibility = ALL_MEMBERS）でアクセスされた場合、
 * confirmedAt / confirmedVia / excludedAt は NULL マスクされ、
 * 未確認者のみ user 情報（userId / displayName / avatarUrl）が返る。
 * ADMIN+ 視点では全フィールドがそのまま返る。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationRecipientResponse {

    private Long id;

    /** 受信者ユーザーID */
    private Long userId;

    /** 受信者表示名 */
    private String displayName;

    /** 受信者アバターURL（未設定なら NULL） */
    private String avatarUrl;

    /** 確認済みフラグ */
    private Boolean isConfirmed;

    /** 確認日時（未確認の場合 NULL。MEMBER 視点では常に NULL マスク） */
    private LocalDateTime confirmedAt;

    /** 確認経路（APP / TOKEN / BULK）（未確認の場合 NULL。MEMBER 視点では常に NULL マスク） */
    private ConfirmedVia confirmedVia;

    /** 除外（確認免除）日時（NULL の場合は除外されていない。MEMBER 視点では常に NULL マスク） */
    private LocalDateTime excludedAt;

    private LocalDateTime createdAt;
}
