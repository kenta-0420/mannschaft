package com.mannschaft.app.workflow.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.workflow.ApprovalType;
import com.mannschaft.app.workflow.ApproverType;
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

/**
 * ワークフローテンプレートステップエンティティ。テンプレート内の承認ステップ定義を管理する。
 */
@Entity
@Table(name = "workflow_template_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WorkflowTemplateStepEntity extends BaseEntity {

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false)
    private Integer stepOrder;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ApprovalType approvalType = ApprovalType.ALL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApproverType approverType;

    @Column(columnDefinition = "JSON")
    private String approverUserIds;

    @Column(length = 30)
    private String approverRole;

    private Short autoApproveDays;
}
