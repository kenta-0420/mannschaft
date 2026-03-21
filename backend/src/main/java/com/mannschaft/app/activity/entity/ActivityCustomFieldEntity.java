package com.mannschaft.app.activity.entity;

import com.mannschaft.app.activity.FieldScope;
import com.mannschaft.app.activity.FieldType;
import com.mannschaft.app.common.BaseEntity;
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
 * 活動記録カスタムフィールド定義エンティティ。
 */
@Entity
@Table(name = "activity_custom_fields")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActivityCustomFieldEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private FieldScope scope = FieldScope.ACTIVITY;

    @Column(nullable = false, length = 100)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FieldType fieldType;

    @Column(columnDefinition = "JSON")
    private String options;

    @Column(length = 20)
    private String unit;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRequired = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * フィールドを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * フィールド情報を更新する。
     */
    public void update(String fieldName, String options, String unit,
                       Boolean isRequired, Integer sortOrder) {
        this.fieldName = fieldName;
        this.options = options;
        this.unit = unit;
        this.isRequired = isRequired;
        this.sortOrder = sortOrder;
    }
}
