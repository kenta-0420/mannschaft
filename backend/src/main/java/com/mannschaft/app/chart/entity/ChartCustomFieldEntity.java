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
 * カスタム項目定義エンティティ。
 */
@Entity
@Table(name = "chart_custom_fields")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChartCustomFieldEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 100)
    private String fieldName;

    @Column(nullable = false, length = 20)
    private String fieldType;

    @Column(columnDefinition = "JSON")
    private String options;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * カスタムフィールドを更新する。
     */
    public void update(String fieldName, String fieldType, String options, Integer sortOrder) {
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.options = options;
        this.sortOrder = sortOrder;
    }

    /**
     * 論理無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }
}
