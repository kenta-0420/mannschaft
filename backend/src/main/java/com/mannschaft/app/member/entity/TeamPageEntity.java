package com.mannschaft.app.member.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.member.PageStatus;
import com.mannschaft.app.member.PageType;
import com.mannschaft.app.member.PageVisibility;
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
 * メンバー紹介ページエンティティ。メインページまたは年度別ページを管理する。
 */
@Entity
@Table(name = "team_pages")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamPageEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PageType pageType;

    private Short year;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 500)
    private String coverImageS3Key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PageVisibility visibility = PageVisibility.MEMBERS_ONLY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PageStatus status = PageStatus.DRAFT;

    @Column(length = 64)
    private String previewToken;

    private LocalDateTime previewTokenExpiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allowSelfEdit = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * ページ情報を更新する。
     */
    public void update(String title, String slug, String description,
                       String coverImageS3Key, PageVisibility visibility,
                       Boolean allowSelfEdit, Integer sortOrder) {
        this.title = title;
        this.slug = slug;
        this.description = description;
        this.coverImageS3Key = coverImageS3Key;
        this.visibility = visibility;
        this.allowSelfEdit = allowSelfEdit;
        this.sortOrder = sortOrder;
    }

    /**
     * 公開ステータスを変更する。
     */
    public void changeStatus(PageStatus status) {
        this.status = status;
        if (status == PageStatus.PUBLISHED) {
            this.previewToken = null;
            this.previewTokenExpiresAt = null;
        }
    }

    /**
     * プレビュートークンを設定する。
     */
    public void setPreviewToken(String token, LocalDateTime expiresAt) {
        this.previewToken = token;
        this.previewTokenExpiresAt = expiresAt;
    }

    /**
     * プレビュートークンを無効化する。
     */
    public void clearPreviewToken() {
        this.previewToken = null;
        this.previewTokenExpiresAt = null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
