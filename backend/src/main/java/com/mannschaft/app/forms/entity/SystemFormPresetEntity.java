package com.mannschaft.app.forms.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * システムフォームプリセットエンティティ。運営が管理する定型テンプレートプリセットを管理する。
 */
@Entity
@Table(name = "system_form_presets")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SystemFormPresetEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 50)
    private String category;

    @Column(nullable = false, columnDefinition = "JSON")
    private String fieldsJson;

    @Column(length = 50)
    private String icon;

    @Column(length = 7)
    private String color;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * プリセットを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * プリセットを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * 更新リクエストの非 null フィールドを反映する。
     *
     * <p>managed entity を直接ミューテートすることで主キー（BaseEntity の id）を保持し、
     * save 時に確実に UPDATE となるようにする（toBuilder().build() は id を引き継がず INSERT 化するため使用しない）。
     */
    public void applyUpdate(
            String name,
            String description,
            String category,
            String fieldsJson,
            String icon,
            String color) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (category != null) {
            this.category = category;
        }
        if (fieldsJson != null) {
            this.fieldsJson = fieldsJson;
        }
        if (icon != null) {
            this.icon = icon;
        }
        if (color != null) {
            this.color = color;
        }
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
