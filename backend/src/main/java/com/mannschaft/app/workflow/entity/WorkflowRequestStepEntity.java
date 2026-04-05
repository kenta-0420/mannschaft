package com.mannschaft.app.workflow.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.workflow.StepStatus;
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
 * ワークフロー申請ステップエンティティ。申請内の各承認ステップの状態を管理する。
 */
@Entity
@Table(name = "workflow_request_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WorkflowRequestStepEntity extends BaseEntity {

    @Column(nullable = false)
    private Long requestId;

    @Column(nullable = false)
    private Integer stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StepStatus status = StepStatus.WAITING;

    private LocalDateTime completedAt;

    /**
     * ステップを進行中にする。
     */
    public void startProgress() {
        this.status = StepStatus.IN_PROGRESS;
    }

    /**
     * ステップを承認済みにする。
     */
    public void approve() {
        this.status = StepStatus.APPROVED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * ステップを却下する。
     */
    public void reject() {
        this.status = StepStatus.REJECTED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * ステップをスキップする。
     */
    public void skip() {
        this.status = StepStatus.SKIPPED;
        this.completedAt = LocalDateTime.now();
    }
}
