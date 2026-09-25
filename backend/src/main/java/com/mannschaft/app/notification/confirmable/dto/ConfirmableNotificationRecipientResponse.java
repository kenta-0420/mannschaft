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

    /** 受信者表示名（退会者は個人情報を出さないため NULL） */
    private String displayName;

    /** 受信者アバターURL（未設定・退会者は NULL） */
    private String avatarUrl;

    /**
     * CMP-260920-1040是正: 受信者が退会済みかどうか（家老の検出・殿の確認）。
     *
     * <p>{@code UserEntity} は {@code @SQLRestriction("deleted_at IS NULL")} を持つため、
     * 受信者の {@code user} を JPA の LAZY 関連経由で読むと、退会者は
     * {@code EntityNotFoundException} になり一覧全体が 500 化していた。本フィールドは
     * その根治として、関連経由ではなく退会者にも対応した投影（native/JOIN）で作る
     * レスポンスに載せる「退会している」ことを示す項目。true の間は
     * {@code displayName}/{@code avatarUrl} は NULL（個人情報を出さない）。</p>
     */
    private boolean withdrawn;

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
