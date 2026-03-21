package com.mannschaft.app.workflow.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.workflow.WorkflowFieldType;
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
 * ワークフローテンプレートフィールドエンティティ。テンプレートの入力フィールド定義を管理する。
 */
@Entity
@Table(name = "workflow_template_fields")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WorkflowTemplateFieldEntity extends BaseEntity {

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 50)
    private String fieldKey;

    @Column(nullable = false, length = 100)
    private String fieldLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkflowFieldType fieldType;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRequired = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(columnDefinition = "JSON")
    private String optionsJson;
}
