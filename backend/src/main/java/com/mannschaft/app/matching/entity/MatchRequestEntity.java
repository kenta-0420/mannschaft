package com.mannschaft.app.matching.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.matching.ActivityType;
import com.mannschaft.app.matching.MatchCategory;
import com.mannschaft.app.matching.MatchLevel;
import com.mannschaft.app.matching.MatchRequestStatus;
import com.mannschaft.app.matching.MatchVisibility;
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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 対外交流の募集投稿エンティティ。
 */
@Entity
@Table(name = "match_requests")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MatchRequestEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityType activityType;

    @Column(length = 50)
    private String activityDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MatchCategory category = MatchCategory.ANY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MatchVisibility visibility = MatchVisibility.PLATFORM;

    @Column(nullable = false, length = 2)
    private String prefectureCode;

    @Column(length = 5)
    private String cityCode;

    @Column(length = 200)
    private String venueName;

    private LocalDate preferredDateFrom;

    private LocalDate preferredDateTo;

    private LocalTime preferredTimeFrom;

    private LocalTime preferredTimeTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MatchLevel level = MatchLevel.ANY;

    private Short minParticipants;

    private Short maxParticipants;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MatchRequestStatus status = MatchRequestStatus.OPEN;

    @Column(nullable = false)
    @Builder.Default
    private Integer proposalCount = 0;

    private LocalDateTime expiresAt;

    private Long matchedProposalId;

    @Column(nullable = false)
    @Builder.Default
    private Short cancelCount = 0;

    private LocalDateTime deletedAt;

    /**
     * 募集内容を更新する。
     */
    public void update(String title, String description, ActivityType activityType,
                       String activityDetail, MatchCategory category, MatchVisibility visibility,
                       String prefectureCode, String cityCode, String venueName,
                       LocalDate preferredDateFrom, LocalDate preferredDateTo,
                       LocalTime preferredTimeFrom, LocalTime preferredTimeTo,
                       MatchLevel level, Short minParticipants, Short maxParticipants,
                       LocalDateTime expiresAt) {
        this.title = title;
        this.description = description;
        this.activityType = activityType;
        this.activityDetail = activityDetail;
        this.category = category;
        this.visibility = visibility;
        this.prefectureCode = prefectureCode;
        this.cityCode = cityCode;
        this.venueName = venueName;
        this.preferredDateFrom = preferredDateFrom;
        this.preferredDateTo = preferredDateTo;
        this.preferredTimeFrom = preferredTimeFrom;
        this.preferredTimeTo = preferredTimeTo;
        this.level = level;
        this.minParticipants = minParticipants;
        this.maxParticipants = maxParticipants;
        this.expiresAt = expiresAt;
    }

    /**
     * マッチング成立を設定する。
     */
    public void markMatched(Long proposalId) {
        this.status = MatchRequestStatus.MATCHED;
        this.matchedProposalId = proposalId;
    }

    /**
     * キャンセル後にOPENへ復元する。
     */
    public void reopenAfterCancel() {
        this.status = MatchRequestStatus.OPEN;
        this.matchedProposalId = null;
    }

    /**
     * 期限切れにする。
     */
    public void expire() {
        this.status = MatchRequestStatus.EXPIRED;
    }

    /**
     * 応募数をインクリメントする。
     */
    public void incrementProposalCount() {
        this.proposalCount++;
    }

    /**
     * 応募数をデクリメントする。
     */
    public void decrementProposalCount() {
        if (this.proposalCount > 0) {
            this.proposalCount--;
        }
    }

    /**
     * キャンセル回数をインクリメントする。
     */
    public void incrementCancelCount() {
        this.cancelCount++;
    }

    /**
     * キャンセル回数をデクリメントする（合意キャンセル確定時）。
     */
    public void decrementCancelCount() {
        if (this.cancelCount > 0) {
            this.cancelCount--;
        }
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 編集可能かどうかを判定する。
     */
    public boolean isEditable() {
        return this.status == MatchRequestStatus.OPEN;
    }
}
