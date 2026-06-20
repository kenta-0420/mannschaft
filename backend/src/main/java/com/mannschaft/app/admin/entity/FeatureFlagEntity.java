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
import lombok.experimental.SuperBuilder;

/**
 * フィーチャーフラグエンティティ。機能の有効/無効を管理する。
 */
@Entity
@Table(name = "feature_flags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
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

    /**
     * 説明文を更新する（部分更新）。
     *
     * <p>managed entity をその場でミューテートする更新メソッド。{@code @Transactional} 内で
     * 呼ぶことで JPA の dirty checking により UPDATE が発行される。</p>
     *
     * <p><strong>なぜ builder ({@code toBuilder().build()}) で作り直さないか:</strong>
     * 本エンティティは {@code @SuperBuilder(toBuilder = true)} を使用しており、
     * 主キー {@code id} は基底クラス {@link BaseEntity} のフィールドである。
     * {@code toBuilder()} は {@code id} を引き継ぐが、managed entity の直接ミューテートが
     * より安全かつ明示的なため、その場でフィールドを更新する。よって直接ミューテートする。</p>
     *
     * @param description 新しい説明文
     */
    public void updateDescription(String description) {
        this.description = description;
    }
}
