package com.mannschaft.app.activity.entity;

import com.mannschaft.app.activity.PresetCategory;
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
 * プラットフォーム標準テンプレートプリセットエンティティ。
 */
@Entity
@Table(name = "system_activity_template_presets")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SystemActivityTemplatePresetEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PresetCategory category;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 30)
    private String icon;

    @Column(length = 7)
    private String color;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isParticipantRequired = true;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String defaultVisibility = "MEMBERS_ONLY";

    @Column(nullable = false, columnDefinition = "JSON")
    private String fieldsJson;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    private LocalDateTime deletedAt;

    /**
     * プリセット情報を更新する。
     */
    public void update(String name, String description, String icon, String color,
                       Boolean isParticipantRequired, String defaultVisibility, String fieldsJson) {
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.color = color;
        this.isParticipantRequired = isParticipantRequired;
        this.defaultVisibility = defaultVisibility;
        this.fieldsJson = fieldsJson;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
