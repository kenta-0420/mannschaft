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

    /**
     * 説明文を更新する（部分更新）。
     *
     * <p>managed entity をその場でミューテートする更新メソッド。{@code @Transactional} 内で
     * 呼ぶことで JPA の dirty checking により UPDATE が発行される。</p>
     *
     * <p><strong>なぜ builder ({@code toBuilder().build()}) で作り直さないか:</strong>
     * 本エンティティは {@code @Builder(toBuilder = true)}（{@code @SuperBuilder} ではない）で、
     * 主キー {@code id} は基底クラス {@link BaseEntity} のフィールドである。
     * {@code @Builder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと {@code id = null} の新インスタンスになり、{@code save} が UPDATE でなく
     * INSERT を実行して {@code flag_key} 一意制約違反で 500 になる。よって直接ミューテートする。</p>
     *
     * @param description 新しい説明文
     */
    public void updateDescription(String description) {
        this.description = description;
    }
}
