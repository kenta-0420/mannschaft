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

/**
 * デジタルサイネージ スロットエンティティ。
 * ON DELETE CASCADE により、親画面削除時に物理削除される。
 */
@Entity
@Table(name = "signage_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
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
