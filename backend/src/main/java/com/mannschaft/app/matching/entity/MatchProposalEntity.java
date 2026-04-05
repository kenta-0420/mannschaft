package com.mannschaft.app.matching.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.matching.CancellationType;
import com.mannschaft.app.matching.MatchProposalStatus;
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
 * 募集への応募エンティティ。
 */
@Entity
@Table(name = "match_proposals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MatchProposalEntity extends BaseEntity {

    @Column(nullable = false)
    private Long requestId;

    @Column(nullable = false)
    private Long proposingTeamId;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 200)
    private String proposedVenue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MatchProposalStatus status = MatchProposalStatus.PENDING;

    @Column(length = 500)
    private String statusReason;

    private Long cancelledByTeamId;

    @Enumerated(EnumType.STRING)
    private CancellationType cancellationType;

    private LocalDateTime mutualAgreedAt;

    /**
     * 応募を承諾する。
     */
    public void accept() {
        this.status = MatchProposalStatus.ACCEPTED;
    }

    /**
     * 応募を拒否する。
     */
    public void reject(String reason) {
        this.status = MatchProposalStatus.REJECTED;
        this.statusReason = reason;
    }

    /**
     * 応募を取り下げる。
     */
    public void withdraw(String reason) {
        this.status = MatchProposalStatus.WITHDRAWN;
        this.statusReason = reason;
    }

    /**
     * マッチング成立後のキャンセルを行う。
     */
    public void cancel(Long cancelledByTeamId, String reason, boolean mutual) {
        this.status = MatchProposalStatus.CANCELLED;
        this.cancelledByTeamId = cancelledByTeamId;
        this.statusReason = reason;
        this.cancellationType = mutual ? CancellationType.MUTUAL_PENDING : CancellationType.UNILATERAL;
    }

    /**
     * 合意キャンセルを承認する。
     */
    public void agreeCancellation() {
        this.cancellationType = CancellationType.MUTUAL;
        this.mutualAgreedAt = LocalDateTime.now();
    }

    /**
     * 合意キャンセル期限切れで一方的に確定する。
     */
    public void expireMutualPending() {
        this.cancellationType = CancellationType.UNILATERAL;
    }
}
