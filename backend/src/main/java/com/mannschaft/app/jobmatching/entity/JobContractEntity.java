package com.mannschaft.app.jobmatching.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
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

import java.time.LocalDateTime;

/**
 * 求人契約エンティティ。F13.1 Phase 13.1.1 MVP。
 *
 * <p>論理削除なし（CANCELLED ステータスで表現）。{@link Version} による楽観的ロックを使用する。</p>
 * <p>{@code chatRoomId} はチャット自動作成で生成した chat_channels.id を格納予定だが、
 * 既存 DB に chat_rooms テーブルは存在しないため FK 制約は未設定（Phase 13.1.2 以降で接続）。</p>
 */
@Entity
@Table(name = "job_contracts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class JobContractEntity extends BaseEntity {

    @Column(name = "job_posting_id", nullable = false)
    private Long jobPostingId;

    @Column(name = "job_application_id", nullable = false)
    private Long jobApplicationId;

    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    @Column(name = "worker_user_id", nullable = false)
    private Long workerUserId;

    @Column(name = "chat_room_id")
    private Long chatRoomId;

    @Column(name = "base_reward_jpy", nullable = false)
    private Integer baseRewardJpy;

    @Column(name = "work_start_at", nullable = false)
    private LocalDateTime workStartAt;

    @Column(name = "work_end_at", nullable = false)
    private LocalDateTime workEndAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobContractStatus status;

    @Column(name = "matched_at", nullable = false)
    private LocalDateTime matchedAt;

    @Column(name = "completion_reported_at")
    private LocalDateTime completionReportedAt;

    @Column(name = "completion_approved_at")
    private LocalDateTime completionApprovedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "rejection_count", nullable = false)
    @Builder.Default
    private Integer rejectionCount = 0;

    @Column(name = "last_rejection_reason", columnDefinition = "TEXT")
    private String lastRejectionReason;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 0;

    /**
     * チャットルーム（chat_channels.id）を割り当てる。
     */
    public void assignChatRoom(Long chatRoomId) {
        this.chatRoomId = chatRoomId;
    }

    /**
     * Worker の完了報告を記録する。
     */
    public void reportCompletion() {
        this.status = JobContractStatus.COMPLETION_REPORTED;
        this.completionReportedAt = LocalDateTime.now();
    }

    /**
     * Requester が完了承認する（MVP では COMPLETED へ、後続 Phase で AUTHORIZED へ変更予定）。
     */
    public void approveCompletion() {
        this.status = JobContractStatus.COMPLETED;
        this.completionApprovedAt = LocalDateTime.now();
    }

    /**
     * Requester が差し戻しする。
     *
     * @param reason 差し戻し理由
     */
    public void rejectCompletion(String reason) {
        this.status = JobContractStatus.IN_PROGRESS;
        this.lastRejectionReason = reason;
        this.rejectionCount = this.rejectionCount + 1;
    }

    /**
     * 契約をキャンセルする。
     */
    public void cancel() {
        this.status = JobContractStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }
}
