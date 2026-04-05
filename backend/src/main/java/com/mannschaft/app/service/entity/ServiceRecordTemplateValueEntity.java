package com.mannschaft.app.service.entity;

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
 * テンプレートのカスタムフィールド初期値エンティティ。
 */
@Entity
@Table(name = "service_record_template_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ServiceRecordTemplateValueEntity extends BaseEntity {

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false)
    private Long fieldId;

    @Column(columnDefinition = "TEXT")
    private String defaultValue;

    /**
     * デフォルト値を更新する。
     */
    public void updateDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }
}
