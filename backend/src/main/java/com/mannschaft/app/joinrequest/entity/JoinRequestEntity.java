package com.mannschaft.app.joinrequest.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * MEMBER 参加申請エンティティ（柱③-A・CMP-260901-1538）。
 *
 * <p>PUBLIC な TEAM/ORGANIZATION（{@code lifecycle_status = ACTIVE} のみ）への MEMBER としての
 * 参加申請を表す。{@code invite_tokens} と同じ流儀（{@link #teamId} / {@link #organizationId} の
 * どちらか一方のみ非 NULL）でスコープを表現する（クロスドメイン FK 禁止・原則1）。</p>
 *
 * <p>PENDING 中の重複申請は {@code UNIQUE(scope, requester, status)}（TEAM/ORGANIZATION 双方）で
 * DB 層でも拒否する。サービス層の事前チェックはこれの一次防御に過ぎず、競合時の
 * {@code DataIntegrityViolationException} をサービス層で捕捉し既存 PENDING 行へ冪等応答する
 * （二重防御。DB migration: {@code V203.20260905143506__create_join_requests_table.sql}）。</p>
 *
 * <p>日時は全て {@link Instant}（起きた瞬間）で保持する。壁時計ではなく瞬間であり、
 * 番人 {@code DateTimeAndZoneGuardTest} が新規の {@code LocalDateTime} フィールドを禁じている
 * （金型: {@code VillageInvitationEntity}）。</p>
 */
@Entity
@Table(
        name = "join_requests",
        // 本番 Flyway V203.20260905143506 の uk_jr_team_pending / uk_jr_org_pending を
        // uniqueConstraints で宣言する。IT は ddl-auto=create（Hibernate が Entity から表生成・
        // Flyway 非経由）のため、これが無いと IT 表に UNIQUE 制約が作られず、PENDING 中の
        // 重複申請を DB 層で拒否する検証（検分P1-1/P2）が偽陽性で通ってしまう
        // （金型: AdDailyStatsEntity#uk_campaign_ad_date）。
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_jr_team_pending", columnNames = {"team_id", "requester_user_id", "status"}),
                @UniqueConstraint(name = "uk_jr_org_pending", columnNames = {"organization_id", "requester_user_id", "status"})
        })
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
    private Instant reviewedAt;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
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
