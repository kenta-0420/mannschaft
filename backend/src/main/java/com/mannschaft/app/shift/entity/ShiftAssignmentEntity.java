package com.mannschaft.app.shift.entity;

import com.mannschaft.app.shift.ShiftAssignmentStatus;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * シフト割当エンティティ。スロットへの割当（提案・確定・取消）を管理する。
 */
@Entity
@Table(name = "shift_assignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftAssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long slotId;

    @Column(nullable = false)
    private Long userId;

    /** NULL = 手動割当 */
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShiftAssignmentStatus status = ShiftAssignmentStatus.PROPOSED;

    /** 貪欲法スコア（手動割当時は NULL） */
    @Column(precision = 8, scale = 4)
    private BigDecimal score;

    @Column(nullable = false)
    private Long assignedBy;

    @Column(length = 500)
    private String note;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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
     * 割当を確定する。
     */
    public void confirm() {
        this.status = ShiftAssignmentStatus.CONFIRMED;
    }

    /**
     * 割当を取消する。
     */
    public void revoke() {
        this.status = ShiftAssignmentStatus.REVOKED;
    }
}
