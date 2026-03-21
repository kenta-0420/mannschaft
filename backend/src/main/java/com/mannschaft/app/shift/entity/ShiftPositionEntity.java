package com.mannschaft.app.shift.entity;

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
 * シフトポジションエンティティ。チーム内のシフト役割を定義する。
 */
@Entity
@Table(name = "shift_positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftPositionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * ポジション名を変更する。
     *
     * @param name 新しいポジション名
     */
    public void changeName(String name) {
        this.name = name;
    }

    /**
     * 表示順を変更する。
     *
     * @param displayOrder 新しい表示順
     */
    public void changeDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    /**
     * ポジションを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * ポジションを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }
}
