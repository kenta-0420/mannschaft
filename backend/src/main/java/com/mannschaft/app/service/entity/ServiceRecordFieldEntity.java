package com.mannschaft.app.service.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.service.FieldType;
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
 * カスタムフィールド定義エンティティ。
 */
@Entity
@Table(name = "service_record_fields")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ServiceRecordFieldEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 100)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FieldType fieldType;

    @Column(length = 500)
    private String description;

    @Column(columnDefinition = "JSON")
    private String options;

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
     * フィールド定義を更新する。
     */
    public void update(String fieldName, FieldType fieldType, String description,
                       String options, Boolean isRequired, Integer sortOrder, Boolean isActive) {
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.description = description;
        this.options = options;
        this.isRequired = isRequired;
        this.sortOrder = sortOrder;
        this.isActive = isActive;
    }

    /**
     * 無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 並び順を更新する。
     */
    public void updateSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
