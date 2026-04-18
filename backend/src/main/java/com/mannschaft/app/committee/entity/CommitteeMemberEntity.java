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
 * 委員会メンバーエンティティ。委員会への参加履歴を管理する。
 */
@Entity
@Table(name = "committee_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CommitteeMemberEntity extends BaseEntity {

    /** 委員会ID */
    @Column(nullable = false)
    private Long committeeId;

    /** ユーザーID */
    @Column(nullable = false)
    private Long userId;

    /** 委員会ロール */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommitteeRole role;

    /** 参加日時 */
    @Column(nullable = false)
    private LocalDateTime joinedAt;

    /** 離脱日時（null = 現役） */
    private LocalDateTime leftAt;

    /** 招待者ユーザーID */
    private Long invitedBy;

    /**
     * ロールを変更する。
     */
    public void updateRole(CommitteeRole newRole) {
        this.role = newRole;
    }

    /**
     * 離脱処理を行う。
     */
    public void leave() {
        this.leftAt = LocalDateTime.now();
    }
}
