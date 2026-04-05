package com.mannschaft.app.activity.entity;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
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
 * 活動記録テンプレートエンティティ。
 */
@Entity
@Table(name = "activity_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActivityTemplateEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ActivityVisibility defaultVisibility = ActivityVisibility.MEMBERS_ONLY;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * テンプレート情報を更新する。
     */
    public void update(String name, String description, String icon, String color,
                       Boolean isParticipantRequired, ActivityVisibility defaultVisibility,
                       Integer sortOrder) {
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.color = color;
        this.isParticipantRequired = isParticipantRequired;
        this.defaultVisibility = defaultVisibility;
        this.sortOrder = sortOrder;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
