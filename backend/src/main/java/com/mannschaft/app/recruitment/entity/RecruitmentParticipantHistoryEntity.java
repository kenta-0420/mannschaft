package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.recruitment.ParticipantHistoryReason;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
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
 * F03.11 募集型予約: 参加者ステータス遷移の監査履歴。
 * Phase 1 では INSERT 最小限。Phase 3 で WAITLISTED / AUTO_CANCELLED 遷移時に本格活用。
 *
 * BaseEntity は継承しない (テーブルに updated_at が無く changed_at のみのため)。
 */
@Entity
@Table(name = "recruitment_participant_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RecruitmentParticipantHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long participantId;

    @Column(nullable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RecruitmentParticipantStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentParticipantStatus newStatus;

    private Long changedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParticipantHistoryReason changeReason;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    @PrePersist
    protected void onCreate() {
        if (this.changedAt == null) {
            this.changedAt = LocalDateTime.now();
        }
    }
}
