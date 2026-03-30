package com.mannschaft.app.translation.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 翻訳担当者アサインエンティティ。
 * スコープ・言語ごとに翻訳担当ユーザーを管理する。
 */
@Entity
@Table(
        name = "translation_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ta_scope_user_lang",
                columnNames = {"scope_type", "scope_id", "user_id", "language"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TranslationAssignmentEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 10)
    private String language;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * アサインを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * アサインを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }
}
