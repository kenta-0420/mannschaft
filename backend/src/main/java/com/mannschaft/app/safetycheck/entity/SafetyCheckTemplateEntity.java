package com.mannschaft.app.safetycheck.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
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
 * 安否確認テンプレートエンティティ。安否確認の雛形を管理する。
 */
@Entity
@Table(name = "safety_check_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SafetyCheckTemplateEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SafetyCheckScopeType scopeType;

    private Long scopeId;

    @Column(length = 100)
    private String templateName;

    @Column(length = 200)
    private String title;

    @Column(length = 1000)
    private String message;

    private Integer reminderIntervalMinutes;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSystemDefault = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    private Long createdBy;

    /**
     * テンプレートの内容を更新する。
     *
     * @param templateName テンプレート名
     * @param title        タイトル
     * @param message      メッセージ
     * @param reminderIntervalMinutes リマインド間隔（分）
     * @param sortOrder    表示順
     */
    public void update(String templateName, String title, String message,
                       Integer reminderIntervalMinutes, Integer sortOrder) {
        if (templateName != null) this.templateName = templateName;
        if (title != null) this.title = title;
        if (message != null) this.message = message;
        if (reminderIntervalMinutes != null) this.reminderIntervalMinutes = reminderIntervalMinutes;
        if (sortOrder != null) this.sortOrder = sortOrder;
    }
}
