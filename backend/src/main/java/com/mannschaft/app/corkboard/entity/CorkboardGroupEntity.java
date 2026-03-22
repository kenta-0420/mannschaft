package com.mannschaft.app.corkboard.entity;

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
 * コルクボードセクション（グループ）エンティティ。カードをグループ化するためのセクション。
 */
@Entity
@Table(name = "corkboard_groups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CorkboardGroupEntity extends BaseEntity {

    @Column(nullable = false)
    private Long corkboardId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isCollapsed = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer positionX = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer positionY = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer width = 400;

    @Column(nullable = false)
    @Builder.Default
    private Integer height = 300;

    @Column(nullable = false)
    @Builder.Default
    private Short displayOrder = 0;

    /**
     * セクション情報を更新する。
     */
    public void update(String name, Boolean isCollapsed, Integer positionX, Integer positionY,
                       Integer width, Integer height, Short displayOrder) {
        this.name = name;
        this.isCollapsed = isCollapsed;
        this.positionX = positionX;
        this.positionY = positionY;
        this.width = width;
        this.height = height;
        this.displayOrder = displayOrder;
    }
}
