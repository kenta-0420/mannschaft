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
 * カスタムフィールド値エンティティ。
 */
@Entity
@Table(name = "service_record_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ServiceRecordValueEntity extends BaseEntity {

    @Column(nullable = false)
    private Long serviceRecordId;

    @Column(nullable = false)
    private Long fieldId;

    @Column(columnDefinition = "TEXT")
    private String value;

    /**
     * 値を更新する。
     */
    public void updateValue(String value) {
        this.value = value;
    }
}
