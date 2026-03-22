package com.mannschaft.app.admin.entity;

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
 * フィーチャーフラグエンティティ。機能の有効/無効を管理する。
 */
@Entity
@Table(name = "feature_flags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FeatureFlagEntity extends BaseEntity {

    @Column(nullable = false, length = 100, unique = true)
    private String flagKey;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEnabled = false;

    @Column(length = 500)
    private String description;

    private Long updatedBy;

    /**
     * フラグの有効/無効を更新する。
     *
     * @param enabled   有効フラグ
     * @param userId    更新者ID
     */
    public void updateFlag(boolean enabled, Long userId) {
        this.isEnabled = enabled;
        this.updatedBy = userId;
    }
}
