package com.mannschaft.app.shift.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.shift.SwapRequestStatus;
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
}
