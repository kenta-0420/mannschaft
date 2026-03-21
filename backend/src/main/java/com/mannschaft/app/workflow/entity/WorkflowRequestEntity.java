package com.mannschaft.app.workflow.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.workflow.WorkflowStatus;
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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * ワークフロー申請エンティティ。テンプレートに基づく個別の申請を管理する。
 */
@Entity
@Table(name = "workflow_requests")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WorkflowRequestEntity extends BaseEntity {

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WorkflowStatus status = WorkflowStatus.DRAFT;

    private Long requestedBy;

    private LocalDateTime requestedAt;

    private Integer currentStepOrder;

    @Column(columnDefinition = "JSON")
    private String fieldValues;

    @Version
    private Long version;

    @Column(length = 30)
    private String sourceType;

    private Long sourceId;

    private LocalDateTime deletedAt;

    /**
     * 申請を提出する。
     */
    public void submit() {
        this.status = WorkflowStatus.PENDING;
        this.requestedAt = LocalDateTime.now();
        this.currentStepOrder = 1;
    }

    /**
     * 進行中にする（最初のステップ承認開始時）。
     */
    public void startProgress() {
        this.status = WorkflowStatus.IN_PROGRESS;
    }

    /**
     * 現在のステップを次へ進める。
     *
     * @param nextStepOrder 次のステップ順序
     */
    public void advanceStep(int nextStepOrder) {
        this.currentStepOrder = nextStepOrder;
    }

    /**
     * 申請を承認済みにする。
     */
    public void approve() {
        this.status = WorkflowStatus.APPROVED;
    }

    /**
     * 申請を却下する。
     */
    public void reject() {
        this.status = WorkflowStatus.REJECTED;
    }

    /**
     * 申請を取り下げる。
     */
    public void withdraw() {
        this.status = WorkflowStatus.WITHDRAWN;
    }

    /**
     * 申請をキャンセルする。
     */
    public void cancel() {
        this.status = WorkflowStatus.CANCELLED;
    }

    /**
     * フィールド値を更新する。
     *
     * @param fieldValues フィールド値JSON
     */
    public void updateFieldValues(String fieldValues) {
        this.fieldValues = fieldValues;
    }

    /**
     * タイトルを更新する。
     *
     * @param title タイトル
     */
    public void updateTitle(String title) {
        this.title = title;
    }

    /**
     * 提出可能かどうかを判定する。
     *
     * @return DRAFT ステータスの場合 true
     */
    public boolean isSubmittable() {
        return this.status == WorkflowStatus.DRAFT;
    }

    /**
     * 取り下げ可能かどうかを判定する。
     *
     * @return PENDING または IN_PROGRESS ステータスの場合 true
     */
    public boolean isWithdrawable() {
        return this.status == WorkflowStatus.PENDING
                || this.status == WorkflowStatus.IN_PROGRESS;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
