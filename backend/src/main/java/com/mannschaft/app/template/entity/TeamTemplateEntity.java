package com.mannschaft.app.template.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * チームテンプレートエンティティ。テンプレートの基本情報を管理する。
 */
@Entity
@Table(name = "team_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class TeamTemplateEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String slug;

    @Column(length = 500)
    private String description;

    @Column(length = 500)
    private String iconUrl;

    @Column(length = 50)
    private String category;

    @Column(nullable = false)
    private Boolean isActive;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * テンプレートの各フィールドを更新する（toBuilder を使わない直接ミューテート）。
     * null の場合は既存値を保持する。
     */
    public void applyUpdate(String name, String description, String iconUrl,
                             String category, Boolean isActive) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (iconUrl != null) {
            this.iconUrl = iconUrl;
        }
        if (category != null) {
            this.category = category;
        }
        if (isActive != null) {
            this.isActive = isActive;
        }
    }

    /**
     * テンプレートを論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * テンプレートの論理削除を取り消す。
     */
    public void restore() {
        this.deletedAt = null;
    }
}
