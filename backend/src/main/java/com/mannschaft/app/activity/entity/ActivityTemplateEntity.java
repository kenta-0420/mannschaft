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

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 50)
    private String icon;

    @Column(length = 7)
    private String color;

    @Column(length = 200)
    private String defaultTitlePattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ActivityVisibility defaultVisibility = ActivityVisibility.MEMBERS_ONLY;

    @Column(length = 200)
    private String defaultLocation;

    private Long sourceTemplateId;

    @Column(length = 20)
    private String shareCode;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isShared = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isOfficial = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer useCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer importCount = 0;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * テンプレート情報を更新する。
     */
    public void update(String name, String description, String icon, String color,
                       String defaultTitlePattern, ActivityVisibility defaultVisibility,
                       String defaultLocation) {
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.color = color;
        this.defaultTitlePattern = defaultTitlePattern;
        this.defaultVisibility = defaultVisibility;
        this.defaultLocation = defaultLocation;
    }

    /**
     * 使用回数をインクリメントする。
     */
    public void incrementUseCount() {
        this.useCount++;
    }

    /**
     * インポート回数をインクリメントする。
     */
    public void incrementImportCount() {
        this.importCount++;
    }

    /**
     * 共有を有効化する。
     */
    public void enableShare(String shareCode) {
        this.isShared = true;
        this.shareCode = shareCode;
    }

    /**
     * 共有を無効化する。
     */
    public void disableShare() {
        this.isShared = false;
        this.shareCode = null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
