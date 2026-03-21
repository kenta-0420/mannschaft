package com.mannschaft.app.member.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.member.SectionType;
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

/**
 * ページ内セクションエンティティ。テキスト・画像・メンバー一覧・見出しを管理する。
 */
@Entity
@Table(name = "team_page_sections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamPageSectionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamPageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SectionType sectionType;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 500)
    private String imageS3Key;

    @Column(length = 200)
    private String imageCaption;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * セクション情報を更新する。
     */
    public void update(String title, String content, String imageS3Key,
                       String imageCaption, Integer sortOrder) {
        this.title = title;
        this.content = content;
        this.imageS3Key = imageS3Key;
        this.imageCaption = imageCaption;
        this.sortOrder = sortOrder;
    }
}
