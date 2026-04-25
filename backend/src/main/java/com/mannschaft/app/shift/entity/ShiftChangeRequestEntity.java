package com.mannschaft.app.shift.entity;

import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.ChangeRequestType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * シフト変更依頼エンティティ。
 * A-1確定前変更・A-2個別交代・A-3オープンコールの3種別を統合管理する。
 */
@Entity
@Table(name = "shift_change_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftChangeRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 対象スケジュールID */
    @Column(nullable = false)
    private Long scheduleId;

    /** 対象シフト枠ID（NULL=スケジュール全体への依頼） */
    private Long slotId;

    /** 変更依頼種別 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ChangeRequestType requestType;

    /** ステータス */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ChangeRequestStatus status = ChangeRequestStatus.OPEN;

    /** 依頼者ユーザーID */
    @Column(nullable = false)
    private Long requestedBy;

    /** 依頼理由 */
    @Column(length = 1000)
    private String reason;

    /** 審査者ユーザーID */
    private Long reviewerId;

    /** 審査コメント */
    @Column(length = 500)
    private String reviewComment;

    /** 審査日時 */
    private LocalDateTime reviewedAt;

    /** 有効期限 */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 楽観ロック用バージョン */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 依頼を承認する。
     *
     * @param reviewerId    審査者ユーザーID
     * @param reviewComment 審査コメント
     */
    public void accept(Long reviewerId, String reviewComment) {
        this.status = ChangeRequestStatus.ACCEPTED;
        this.reviewerId = reviewerId;
        this.reviewComment = reviewComment;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 依頼を却下する。
     *
     * @param reviewerId    審査者ユーザーID
     * @param reviewComment 審査コメント
     */
    public void reject(Long reviewerId, String reviewComment) {
        this.status = ChangeRequestStatus.REJECTED;
        this.reviewerId = reviewerId;
        this.reviewComment = reviewComment;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 依頼を取り下げる（依頼者のみ）。
     */
    public void withdraw() {
        this.status = ChangeRequestStatus.WITHDRAWN;
    }
}
