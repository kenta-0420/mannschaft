package com.mannschaft.app.chart.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 問診票テンプレート定義エンティティ。
 */
@Entity
@Table(name = "chart_intake_form_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChartIntakeFormTemplateEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 20)
    private String formType;

    @Column(nullable = false, length = 100)
    private String templateName;

    @Column(nullable = false, columnDefinition = "JSON")
    private String templateJson;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    /**
     * テンプレートを更新する。
     */
    public void update(String templateName, String templateJson, Boolean isDefault) {
        this.templateName = templateName;
        this.templateJson = templateJson;
        this.isDefault = isDefault;
    }
}
