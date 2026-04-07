package com.mannschaft.app.quickmemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 退会 SAGA チェックポイント管理エンティティ。
 * user_id は退会処理中に users レコードが削除されるため非FK。
 * 失敗時に current_step から再開可能。
 */
@Entity
@Table(name = "withdraw_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WithdrawJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 非FK（退会処理中に users レコードが削除される） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** PENDING / IN_PROGRESS / COMPLETED / FAILED / BLOCKED_MANUAL */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "current_step", nullable = false)
    @Builder.Default
    private Integer currentStep = 1;

    /** Step 1: PERSONAL タグ削除 */
    @Column(name = "step_1_completed", nullable = false)
    @Builder.Default
    private Boolean step1Completed = false;

    /** Step 2: TEAM タグ移譲 */
    @Column(name = "step_2_completed", nullable = false)
    @Builder.Default
    private Boolean step2Completed = false;

    /** Step 3: ORGANIZATION タグ移譲 */
    @Column(name = "step_3_completed", nullable = false)
    @Builder.Default
    private Boolean step3Completed = false;

    /** Step 4: quick_memos 物理削除（S3含む） */
    @Column(name = "step_4_completed", nullable = false)
    @Builder.Default
    private Boolean step4Completed = false;

    /** Step 5: user_quick_memo_settings 物理削除 */
    @Column(name = "step_5_completed", nullable = false)
    @Builder.Default
    private Boolean step5Completed = false;

    /** Step 6: 監査ログ匿名化 */
    @Column(name = "step_6_completed", nullable = false)
    @Builder.Default
    private Boolean step6Completed = false;

    /** Step 7: users 物理削除 */
    @Column(name = "step_7_completed", nullable = false)
    @Builder.Default
    private Boolean step7Completed = false;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "last_error_step")
    private Integer lastErrorStep;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void start() {
        this.status = "IN_PROGRESS";
        this.startedAt = LocalDateTime.now();
    }

    public void completeStep(int step) {
        switch (step) {
            case 1 -> { this.step1Completed = true; this.currentStep = 2; }
            case 2 -> { this.step2Completed = true; this.currentStep = 3; }
            case 3 -> { this.step3Completed = true; this.currentStep = 4; }
            case 4 -> { this.step4Completed = true; this.currentStep = 5; }
            case 5 -> { this.step5Completed = true; this.currentStep = 6; }
            case 6 -> { this.step6Completed = true; this.currentStep = 7; }
            case 7 -> {
                this.step7Completed = true;
                this.status = "COMPLETED";
                this.completedAt = LocalDateTime.now();
            }
            default -> throw new IllegalArgumentException("Invalid step: " + step);
        }
    }

    public void fail(int failedStep, String errorMessage) {
        this.status = "FAILED";
        this.lastErrorStep = failedStep;
        this.lastErrorMessage = errorMessage;
        this.retryCount++;
    }

    public void blockForManual(int failedStep, String errorMessage) {
        this.status = "BLOCKED_MANUAL";
        this.lastErrorStep = failedStep;
        this.lastErrorMessage = errorMessage;
    }
}
