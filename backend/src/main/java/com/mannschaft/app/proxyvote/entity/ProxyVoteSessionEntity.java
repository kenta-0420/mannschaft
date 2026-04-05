package com.mannschaft.app.proxyvote.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.proxyvote.ProxyVoteScopeType;
import com.mannschaft.app.proxyvote.QuorumType;
import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 投票セッションエンティティ。総会1回 = 1セッション。
 */
@Entity
@Table(name = "proxy_vote_sessions")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProxyVoteSessionEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProxyVoteScopeType scopeType;

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResolutionMode resolutionMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.DRAFT;

    private LocalDate meetingDate;

    private LocalDateTime votingStartAt;

    private LocalDateTime votingEndAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAnonymous = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private QuorumType quorumType = QuorumType.MAJORITY;

    @Column(precision = 5, scale = 2)
    private BigDecimal quorumThreshold;

    @Column(nullable = false)
    private Integer eligibleCount;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAutoAcceptDelegation = false;

    @Column(columnDefinition = "JSON")
    private String remindBeforeHours;

    private Integer lastRemindHour;

    @Column(nullable = false)
    private Long createdBy;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * セッション情報を更新する。
     */
    public void update(String title, String description, LocalDateTime votingStartAt,
                       LocalDateTime votingEndAt, Boolean isAnonymous, QuorumType quorumType,
                       BigDecimal quorumThreshold, Boolean isAutoAcceptDelegation,
                       ResolutionMode resolutionMode, LocalDate meetingDate, String remindBeforeHours) {
        this.title = title;
        this.description = description;
        this.votingStartAt = votingStartAt;
        this.votingEndAt = votingEndAt;
        this.isAnonymous = isAnonymous;
        this.quorumType = quorumType;
        this.quorumThreshold = quorumThreshold;
        this.isAutoAcceptDelegation = isAutoAcceptDelegation;
        this.resolutionMode = resolutionMode;
        this.meetingDate = meetingDate;
        this.remindBeforeHours = remindBeforeHours;
    }

    /**
     * OPEN 中に更新可能なフィールドのみ更新する。
     */
    public void updateWhenOpen(String title, String description, LocalDateTime votingEndAt,
                               Boolean isAutoAcceptDelegation) {
        this.title = title;
        this.description = description;
        this.votingEndAt = votingEndAt;
        this.isAutoAcceptDelegation = isAutoAcceptDelegation;
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(SessionStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 議決権総数を更新する。
     */
    public void updateEligibleCount(int count) {
        this.eligibleCount = count;
    }

    /**
     * 最後にリマインドした時間ポイントを記録する。
     */
    public void updateLastRemindHour(Integer hour) {
        this.lastRemindHour = hour;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
