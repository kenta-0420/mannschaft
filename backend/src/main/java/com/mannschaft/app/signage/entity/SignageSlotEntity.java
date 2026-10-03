package com.mannschaft.app.signage.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.signage.SignageSlotType;
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
import lombok.experimental.SuperBuilder;

/**
 * デジタルサイネージ スロットエンティティ。
 * ON DELETE CASCADE により、親画面削除時に物理削除される。
 */
@Entity
@Table(name = "signage_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class SignageSlotEntity extends BaseEntity {

    @Column(nullable = false)
    private Long screenId;

    @Column(nullable = false)
    private Integer slotOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SignageSlotType slotType;

    @Column(length = 200)
    private String title;

    private Integer slideDuration;

    /** スロット固有の設定（JSON文字列）。 */
    @Column(columnDefinition = "JSON")
    private String contentConfig;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * スロットの更新可能フィールドを部分更新する（null=現値維持セマンティクス）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ toBuilder().build() で作り直さないか:</strong>
     * {@link SignageSlotEntity} は {@code @SuperBuilder(toBuilder = true)} を使用しており、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code toBuilder()} は {@code id} を引き継ぐが、managed entity の直接ミューテートが
     * より安全かつ明示的なため、その場でフィールドを更新する。
     * よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * @param slideDuration  新スライド秒数（null なら現値維持）
     * @param contentConfig  新コンテンツ設定 JSON（null なら現値維持）
     * @param isActive       新有効フラグ（null なら現値維持）
     */
    public void applyUpdate(Integer slideDuration, String contentConfig, Boolean isActive) {
        if (slideDuration != null) {
            this.slideDuration = slideDuration;
        }
        if (contentConfig != null) {
            this.contentConfig = contentConfig;
        }
        if (isActive != null) {
            this.isActive = isActive;
        }
    }

    /**
     * スロットを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * スロットを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * 表示順を変更する。
     */
    public void changeOrder(int newOrder) {
        this.slotOrder = newOrder;
    }
}
