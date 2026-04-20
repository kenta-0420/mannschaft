package com.mannschaft.app.committee.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 委員会招集状エンティティ。委員会への招集を管理する。
 * 論理削除なし。
 */
@Entity
@Table(name = "committee_invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CommitteeInvitationEntity extends BaseEntity {

    /** 委員会ID */
    @Column(nullable = false)
    private Long committeeId;

    /** 被招集者ユーザーID */
    @Column(nullable = false)
    private Long inviteeUserId;

    /** 提案ロール */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CommitteeRole proposedRole = CommitteeRole.MEMBER;

    /** 招集トークン（UUID） */
    @Column(nullable = false, unique = true, length = 36)
    private String inviteToken;

    /** 招集者ユーザーID */
    private Long invitedBy;

    /** メッセージ */
    @Column(columnDefinition = "TEXT")
    private String message;

    /** 有効期限 */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** 解決日時（null = 未解決） */
    private LocalDateTime resolvedAt;

    /** 解決状態 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CommitteeInvitationResolution resolution;

    /**
     * 招集状を受諾済みとしてマークする。
     */
    public void markAccepted() {
        this.resolvedAt = LocalDateTime.now();
        this.resolution = CommitteeInvitationResolution.ACCEPTED;
    }

    /**
     * 招集状を辞退済みとしてマークする。
     */
    public void markDeclined() {
        this.resolvedAt = LocalDateTime.now();
        this.resolution = CommitteeInvitationResolution.DECLINED;
    }

    /**
     * 招集状をキャンセル済みとしてマークする。
     */
    public void markCancelled() {
        this.resolvedAt = LocalDateTime.now();
        this.resolution = CommitteeInvitationResolution.CANCELLED;
    }

    /**
     * 招集状を期限切れとしてマークする。
     */
    public void markExpired() {
        this.resolvedAt = LocalDateTime.now();
        this.resolution = CommitteeInvitationResolution.EXPIRED;
    }
}
