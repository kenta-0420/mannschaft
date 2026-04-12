package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.recruitment.DisputeResolution;
import com.mannschaft.app.recruitment.NoShowReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * F03.11 Phase 5b: 無断キャンセル（NO_SHOW）記録エンティティ。
 * recruitment_no_show_records テーブルに対応する。
 */
@Entity
@Table(name = "recruitment_no_show_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecruitmentNoShowRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoShowReason reason;

    /** TRUE になると確定済み（24h 仮マーク期間終了）。 */
    @Column(nullable = false)
    private boolean confirmed = false;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "recorded_by")
    private Long recordedBy;

    /** 異議申立済みフラグ。 */
    @Column(nullable = false)
    private boolean disputed = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "dispute_resolution", length = 10)
    private DisputeResolution disputeResolution;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public RecruitmentNoShowRecordEntity(
            Long participantId, Long listingId, Long userId,
            NoShowReason reason, Long recordedBy) {
        this.participantId = participantId;
        this.listingId = listingId;
        this.userId = userId;
        this.reason = reason;
        this.recordedAt = LocalDateTime.now();
        this.recordedBy = recordedBy;
    }

    /** 24h 仮マーク期間終了 → 確定。 */
    public void confirm() {
        this.confirmed = true;
    }

    /** ユーザーが異議申立を行う。 */
    public void dispute() {
        this.disputed = true;
    }

    /** 管理者が異議申立を解決する。 */
    public void resolveDispute(DisputeResolution resolution) {
        this.disputeResolution = resolution;
    }
}
