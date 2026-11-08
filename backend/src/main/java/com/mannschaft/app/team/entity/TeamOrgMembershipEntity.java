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
            columnDefinition = "DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP())")
    private java.time.Instant updatedAt;

    /**
     * 招待の受信通知（{@code TEAM_ORG_INVITE_RECEIVED}）を outbox に予約した瞬間（F01.2.1 2-D・§4.5）。
     * NULL の招待は、招待されたチームがまだ知らない（受信通知の予約の前）。書き込みはネイティブ SQL だけで行う
     * （{@code TeamOrgMembershipRepository#markInviteNotified}）。申請（TEAM_APPLY）では使わない。
     */
    @Column(name = "invite_notified_at", insertable = false, updatable = false)
    private java.time.Instant inviteNotifiedAt;

    /**
     * 作成時点の組織の閉鎖の世代（{@code organizations.closure_generation}。F01.2.1 2-D・§4.5）。作成後は変えない。
     * 組織の世代がこれより進んでいれば（{@link #createdBeforeOrganizationClosure}）、この行は組織の閉鎖より前に作られた行で、
     * 片付け・承認・承諾で無効として扱う（{@code TeamOrgClosureGeneration}）。
     */
    @Column(name = "org_closure_generation", nullable = false, updatable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long orgClosureGeneration;

    /**
     * 作成時点のチームの閉鎖の世代（{@code teams.closure_generation}。F01.2.1 2-D・§4.5）。作成後は変えない。
     * チームの世代がこれより進んでいれば（{@link #createdBeforeTeamClosure}）、この行はチームの閉鎖より前に作られた行。
     */
    @Column(name = "team_closure_generation", nullable = false, updatable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long teamClosureGeneration;

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
        this.updatedAt = java.time.Instant.now();
    }

    @jakarta.persistence.PreUpdate
    protected void onUpdate() {
        this.updatedAt = java.time.Instant.now();
    }

    /**
     * 組織の閉鎖より前に作られた行か（記録した組織の世代 &lt; 組織のいまの世代。F01.2.1 §4.5）。
     *
     * @param currentOrganizationGeneration 組織のいまの閉鎖の世代
     */
    public boolean createdBeforeOrganizationClosure(long currentOrganizationGeneration) {
        return orgClosureGeneration < currentOrganizationGeneration;
    }

    /**
     * チームの閉鎖より前に作られた行か（記録したチームの世代 &lt; チームのいまの世代。F01.2.1 §4.5）。
     *
     * @param currentTeamGeneration チームのいまの閉鎖の世代
     */
    public boolean createdBeforeTeamClosure(long currentTeamGeneration) {
        return teamClosureGeneration < currentTeamGeneration;
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
