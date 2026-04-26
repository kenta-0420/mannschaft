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
import lombok.AllArgsConstructor;
import lombok.Builder;
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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
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
     * 手挙げする（先着1名）。楽観ロックで競合を防ぐ。
     *
     * @param userId 手挙げユーザーID
     */
    public void claim(Long userId) {
        this.claimedBy = userId;
        this.claimedAt = LocalDateTime.now();
        this.status = SwapRequestStatus.CLAIMED;
    }

    /**
     * 候補者を選定して承諾済みにする。
     *
     * @param claimedBy 選定された手挙げユーザーID
     */
    public void selectClaimer(Long claimedBy) {
        this.claimedBy = claimedBy;
        this.accepterId = claimedBy;
        this.status = SwapRequestStatus.ACCEPTED;
    }
}
