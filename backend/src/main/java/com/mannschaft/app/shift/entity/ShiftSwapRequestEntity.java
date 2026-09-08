package com.mannschaft.app.shift.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.shift.SwapRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * シフト交代リクエストエンティティ。メンバー間のシフト交代申請を管理する。
 */
@Entity
@Table(name = "shift_swap_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftSwapRequestEntity extends BaseEntity {

    @Column(nullable = false)
    private Long slotId;

    @Column(nullable = false)
    private Long requesterId;

    private Long accepterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SwapRequestStatus status = SwapRequestStatus.PENDING;

    @Column(length = 500)
    private String reason;

    @Column(length = 500)
    private String adminNote;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

    /** オープンコール（不特定多数募集）フラグ */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isOpenCall = false;

    /** 指定交代相手ユーザーID（is_open_call=false の場合） */
    private Long targetUserId;

    /**
     * 受信者モード。SPECIFIC=特定ユーザー指定 / OPEN_CALL=全体公開。
     * isOpenCall フラグから移行した後継カラム。
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String recipientMode = "SPECIFIC";

    /**
     * 交代対象ユーザーIDリスト（JSON 配列文字列）。SPECIFIC モード時に使用。
     * 例: "[1,2,3]"
     */
    @Column(columnDefinition = "JSON")
    private String targetUserIds;

    /** 手挙げユーザーID（先着1名） */
    private Long claimedBy;

    /** 手挙げ日時 */
    private LocalDateTime claimedAt;

    /** 楽観ロック用バージョン */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    /**
     * 相手が承諾する。
     *
     * @param accepterId 承諾者のユーザーID
     */
    public void accept(Long accepterId) {
        this.accepterId = accepterId;
        this.status = SwapRequestStatus.ACCEPTED;
    }

    /**
     * 管理者が承認する。
     *
     * @param adminId   管理者のユーザーID
     * @param adminNote 管理者メモ
     */
    public void approve(Long adminId, String adminNote) {
        this.status = SwapRequestStatus.APPROVED;
        this.resolvedBy = adminId;
        this.resolvedAt = LocalDateTime.now();
        this.adminNote = adminNote;
    }

    /**
     * 却下する。
     *
     * @param resolvedById 処理者のユーザーID
     * @param adminNote    管理者メモ
     */
    public void reject(Long resolvedById, String adminNote) {
        this.status = SwapRequestStatus.REJECTED;
        this.resolvedBy = resolvedById;
        this.resolvedAt = LocalDateTime.now();
        this.adminNote = adminNote;
    }

    /**
     * キャンセルする。
     */
    public void cancel() {
        this.status = SwapRequestStatus.CANCELLED;
    }

    /**
     * 受信者モードを設定する。
     *
     * @param recipientMode "SPECIFIC" または "OPEN_CALL"
     */
    public void setRecipientMode(String recipientMode) {
        this.recipientMode = recipientMode;
    }

    /**
     * 交代対象ユーザーIDリスト（JSON 配列文字列）を設定する。
     *
     * @param targetUserIds JSON 配列文字列（例: "[1,2,3]"）
     */
    public void setTargetUserIds(String targetUserIds) {
        this.targetUserIds = targetUserIds;
    }
}
