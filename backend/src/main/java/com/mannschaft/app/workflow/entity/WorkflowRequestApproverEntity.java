package com.mannschaft.app.workflow.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.workflow.ApproverDecision;
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
 * ワークフロー申請承認者エンティティ。各ステップの個別承認者の判断を管理する。
 */
@Entity
@Table(name = "workflow_request_approvers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WorkflowRequestApproverEntity extends BaseEntity {

    @Column(nullable = false)
    private Long requestStepId;

    @Column(nullable = false)
    private Long approverUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ApproverDecision decision = ApproverDecision.PENDING;

    private LocalDateTime decisionAt;

    @Column(length = 1000)
    private String decisionComment;

    private Long sealId;

    /**
     * 承認する。
     *
     * @param comment 承認コメント
     * @param sealId  電子印鑑ID（nullの場合は印鑑なし）
     */
    public void approve(String comment, Long sealId) {
        this.decision = ApproverDecision.APPROVED;
        this.decisionAt = LocalDateTime.now();
        this.decisionComment = comment;
        this.sealId = sealId;
    }

    /**
     * 却下する。
     *
     * @param comment 却下コメント
     */
    public void reject(String comment) {
        this.decision = ApproverDecision.REJECTED;
        this.decisionAt = LocalDateTime.now();
        this.decisionComment = comment;
    }

    /**
     * 判断済みかどうかを判定する。
     *
     * @return PENDING 以外の場合 true
     */
    public boolean hasDecided() {
        return this.decision != ApproverDecision.PENDING;
    }
}
