package com.mannschaft.app.moderation.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.moderation.UnflagRequestStatus;
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
 * ヤバいやつ解除申請エンティティ。解除申請フローを管理する。
 */
@Entity
@Table(name = "yabai_unflag_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class YabaiUnflagRequestEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UnflagRequestStatus status = UnflagRequestStatus.PENDING;

    private Long reviewedBy;

    @Column(columnDefinition = "TEXT")
    private String reviewNote;

    private LocalDateTime reviewedAt;

    private LocalDateTime nextEligibleAt;

    /**
     * 解除申請をレビューする。
     *
     * @param reviewerId     レビュアーID
     * @param note           レビューメモ
     * @param newStatus      ACCEPTED/REJECTED
     * @param nextEligible   次回申請可能日時（却下時のみ）
     */
    public void review(Long reviewerId, String note, UnflagRequestStatus newStatus, LocalDateTime nextEligible) {
        this.reviewedBy = reviewerId;
        this.reviewNote = note;
        this.status = newStatus;
        this.reviewedAt = LocalDateTime.now();
        this.nextEligibleAt = nextEligible;
    }
}
