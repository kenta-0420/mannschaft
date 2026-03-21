package com.mannschaft.app.activity.entity;

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
 * 活動レベルのカスタムフィールド値エンティティ。
 */
@Entity
@Table(name = "activity_custom_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActivityCustomValueEntity extends BaseEntity {

    @Column(nullable = false)
    private Long activityResultId;

    @Column(nullable = false)
    private Long customFieldId;

    @Column(nullable = false, length = 15)
    @Builder.Default
    private String scope = "ACTIVITY";

    @Column(columnDefinition = "TEXT")
    private String value;
}
