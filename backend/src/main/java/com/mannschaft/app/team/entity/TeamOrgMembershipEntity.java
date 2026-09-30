package com.mannschaft.app.team.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * チーム−組織所属エンティティ。チームと組織の関連付けを管理する。
 */
@Entity
@Table(name = "team_org_memberships", indexes = {
        @Index(name = "idx_team_org_memberships_org_status_dir",
                columnList = "organization_id, status, direction, invited_at"),
        @Index(name = "idx_team_org_memberships_team_status_dir", columnList = "team_id, status, direction"),
        @Index(name = "idx_team_org_memberships_org_group_status", columnList = "organization_id, group_id, status"),
        @Index(name = "idx_team_org_memberships_status_invited", columnList = "status, invited_at")
})
@Check(name = "chk_team_org_memberships_direction", constraints = "direction IN ('ORG_INVITE','TEAM_APPLY')")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class TeamOrgMembershipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    private Long invitedBy;

    private Long respondedBy;

    @Column(nullable = false)
    private LocalDateTime invitedAt;

    private LocalDateTime respondedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 起点（F01.2.1 §5.3）。既存フローは組織からの招待だけだったため既定は ORG_INVITE。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'ORG_INVITE'")
    @lombok.Builder.Default
    private TeamOrgAffiliationDirection direction = TeamOrgAffiliationDirection.ORG_INVITE;

    /** チームグループID（org_team_groups.id・クロスドメインFKなし）。NULL=未分類。 */
    @Column(name = "group_id")
    private java.util.UUID groupId;

    /** 申請・招待時の添え書き（PENDING の間だけ意味を持つ）。 */
    @Column(length = 500)
    private String message;

    @Column(name = "updated_at", nullable = false,
            columnDefinition = "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;

    /**
     * チーム−組織所属ステータス
     */
    public enum Status {
        PENDING,
        ACTIVE
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @jakarta.persistence.PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 所属申請を承認する。
     */
    public void accept(Long respondedByUserId) {
        this.status = Status.ACTIVE;
        this.respondedBy = respondedByUserId;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * 所属申請を却下する。
     */
    public void reject(Long respondedByUserId) {
        this.respondedBy = respondedByUserId;
        this.respondedAt = LocalDateTime.now();
    }
}
