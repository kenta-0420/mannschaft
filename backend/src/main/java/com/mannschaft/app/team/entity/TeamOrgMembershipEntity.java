package com.mannschaft.app.team.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * チーム−組織所属エンティティ。チームと組織の関連付けを管理する。
 */
@Entity
@Table(name = "team_org_memberships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
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
