package com.mannschaft.app.joinrequest.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * MEMBER 参加申請エンティティ（柱③-A・CMP-260901-1538）。
 *
 * <p>PUBLIC な TEAM/ORGANIZATION（{@code lifecycle_status = ACTIVE} のみ）への MEMBER としての
 * 参加申請を表す。{@code invite_tokens} と同じ流儀（{@link #teamId} / {@link #organizationId} の
 * どちらか一方のみ非 NULL）でスコープを表現する（クロスドメイン FK 禁止・原則1）。</p>
 *
 * <p>PENDING 中の重複申請は冪等に扱う（サービス層で同一申請を返す。DB 制約では強制しない
 * ＝承認/却下後の再申請を新規行として許容するため、{@code UNIQUE(scope, requester, status)} は
 * 張らない）。</p>
 */
@Entity
@Table(name = "join_requests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class JoinRequestEntity extends UuidV7Entity {

    /** チームスコープ時の対象チーム ID（organizationId と排他）。FK は張らない（原則1）。 */
    @Column(name = "team_id")
    private Long teamId;

    /** 組織スコープ時の対象組織 ID（teamId と排他）。FK は張らない（原則1）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /** 申請者ユーザー ID。 */
    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    /** 申請時の任意の一言メッセージ。 */
    @Column(name = "message", length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JoinRequestStatus status;

    /** 審査（承認/却下）した ADMIN/DEPUTY_ADMIN の user ID。 */
    @Column(name = "reviewer_user_id")
    private Long reviewerUserId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** チームスコープの申請か。 */
    public boolean isTeamScope() {
        return this.teamId != null;
    }

    /** スコープ種別文字列（"TEAM" / "ORGANIZATION"）。 */
    public String scopeType() {
        return isTeamScope() ? "TEAM" : "ORGANIZATION";
    }

    /** スコープ ID（teamId または organizationId）。 */
    public Long scopeId() {
        return isTeamScope() ? this.teamId : this.organizationId;
    }
}
