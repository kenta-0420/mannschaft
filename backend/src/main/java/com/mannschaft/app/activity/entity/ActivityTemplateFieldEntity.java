package com.mannschaft.app.activity.entity;

import com.mannschaft.app.activity.FieldType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 活動テンプレートフィールド定義エンティティ。
 */
@Entity
@Table(name = "activity_template_fields")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActivityTemplateFieldEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 50)
    private String fieldKey;

    @Column(nullable = false, length = 100)
    private String fieldLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FieldType fieldType;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRequired = false;

    @Column(columnDefinition = "JSON")
    private String optionsJson;

    @Column(length = 200)
    private String placeholder;

    @Column(length = 20)
    private String unit;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAggregatable = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * フィールド情報を更新する（field_type と field_key は変更不可）。
     */
    public void update(String fieldLabel, Boolean isRequired, String optionsJson,
                       String placeholder, String unit, Boolean isAggregatable, Integer sortOrder) {
        this.fieldLabel = fieldLabel;
        this.isRequired = isRequired;
        this.optionsJson = optionsJson;
        this.placeholder = placeholder;
        this.unit = unit;
        this.isAggregatable = isAggregatable;
        this.sortOrder = sortOrder;
    }
}
