package com.mannschaft.app.jobmatching.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
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
 * 求人応募エンティティ。F13.1 Phase 13.1.1 MVP。
 *
 * <p>論理削除なし・楽観ロックなし。状態は {@link JobApplicationStatus} で管理する。</p>
 */
@Entity
@Table(name = "job_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class JobApplicationEntity extends BaseEntity {

    @Column(name = "job_posting_id", nullable = false)
    private Long jobPostingId;

    @Column(name = "applicant_user_id", nullable = false)
    private Long applicantUserId;

    @Column(name = "self_pr", columnDefinition = "TEXT")
    private String selfPr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobApplicationStatus status;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    /**
     * 採用確定処理。
     *
     * @param deciderUserId 判断したユーザーID（Requester）
     */
    public void accept(Long deciderUserId) {
        this.status = JobApplicationStatus.ACCEPTED;
        this.decidedAt = LocalDateTime.now();
        this.decidedByUserId = deciderUserId;
    }

    /**
     * 不採用処理。
     *
     * @param deciderUserId 判断したユーザーID（Requester）
     */
    public void reject(Long deciderUserId) {
        this.status = JobApplicationStatus.REJECTED;
        this.decidedAt = LocalDateTime.now();
        this.decidedByUserId = deciderUserId;
    }

    /**
     * 応募取り下げ。
     */
    public void withdraw() {
        this.status = JobApplicationStatus.WITHDRAWN;
        this.decidedAt = LocalDateTime.now();
    }
}
