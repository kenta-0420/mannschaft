package com.mannschaft.app.bulletin.entity;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.common.BaseEntity;
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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 掲示板カテゴリエンティティ。スコープごとのカテゴリ情報を管理する。
 */
@Entity
@Table(name = "bulletin_categories")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BulletinCategoryEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 200)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(length = 7)
    private String color;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String postMinRole = "MEMBER_PLUS";

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * カテゴリ情報を更新する。
     *
     * @param name        カテゴリ名
     * @param description 説明
     * @param displayOrder 表示順
     * @param color       カラーコード
     * @param postMinRole 最低投稿権限
     */
    public void update(String name, String description, Integer displayOrder, String color, String postMinRole) {
        this.name = name;
        this.description = description;
        this.displayOrder = displayOrder;
        this.color = color;
        this.postMinRole = postMinRole;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
