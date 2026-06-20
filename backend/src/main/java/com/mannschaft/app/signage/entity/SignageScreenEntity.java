package com.mannschaft.app.signage.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.signage.SignageLayout;
import com.mannschaft.app.signage.SignageTransitionEffect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * デジタルサイネージ 画面エンティティ。
 */
@Entity
@Table(name = "signage_screens")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class SignageScreenEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SignageLayout layout = SignageLayout.LANDSCAPE;

    @Column(nullable = false)
    @Builder.Default
    private Integer defaultSlideDuration = 10;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SignageTransitionEffect transitionEffect = SignageTransitionEffect.FADE;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isClockShown = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isWeatherShown = false;

    @Column(length = 200)
    private String weatherLocation;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String backgroundColor = "#000000";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    private Long createdBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 画面の更新可能フィールドを部分更新する（null=現値維持セマンティクス）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ toBuilder().build() で作り直さないか:</strong>
     * {@link SignageScreenEntity} は {@code @SuperBuilder(toBuilder = true)} を使用しており、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code toBuilder()} は {@code id} を引き継ぐが、managed entity の直接ミューテートが
     * より安全かつ明示的なため、その場でフィールドを更新する。
     * よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * @param name                 新名称（null なら現値維持）
     * @param layout               新レイアウト（null なら現値維持）
     * @param defaultSlideDuration 新デフォルトスライド秒数（null なら現値維持）
     * @param transitionEffect     新トランジションエフェクト（null なら現値維持）
     * @param isActive             新アクティブフラグ（null なら現値維持）
     */
    public void applyUpdate(String name, SignageLayout layout,
                            Integer defaultSlideDuration,
                            SignageTransitionEffect transitionEffect,
                            Boolean isActive) {
        if (name != null) {
            this.name = name;
        }
        if (layout != null) {
            this.layout = layout;
        }
        if (defaultSlideDuration != null) {
            this.defaultSlideDuration = defaultSlideDuration;
        }
        if (transitionEffect != null) {
            this.transitionEffect = transitionEffect;
        }
        if (isActive != null) {
            this.isActive = isActive;
        }
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 画面を無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 画面を有効化する。
     */
    public void activate() {
        this.isActive = true;
    }
}
