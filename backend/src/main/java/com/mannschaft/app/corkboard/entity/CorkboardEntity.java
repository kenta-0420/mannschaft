package com.mannschaft.app.corkboard.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * コルクボードエンティティ。個人・チーム・組織スコープのボードを管理する。
 */
@Entity
@Table(name = "corkboards")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CorkboardEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    private Long scopeId;

    private Long ownerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String backgroundStyle = "CORK";

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String editPolicy = "ADMIN_ONLY";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * ボード情報を更新する。
     *
     * @param name            ボード名
     * @param backgroundStyle 背景スタイル
     * @param editPolicy      編集ポリシー
     * @param isDefault       デフォルトボードか
     */
    public void update(String name, String backgroundStyle, String editPolicy, Boolean isDefault) {
        this.name = name;
        this.backgroundStyle = backgroundStyle;
        this.editPolicy = editPolicy;
        this.isDefault = isDefault;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
