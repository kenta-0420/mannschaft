package com.mannschaft.app.translation.entity;

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
 * 翻訳コンテンツエンティティ。
 * ブログ・お知らせ・ナレッジベース等の原文に対する各言語の翻訳内容を管理する。
 */
@Entity
@Table(name = "content_translations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ContentTranslationEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    /** 翻訳対象のコンテンツ種別（BLOG_POST / ANNOUNCEMENT / KNOWLEDGE_BASE）。 */
    @Column(nullable = false, length = 50)
    private String sourceType;

    @Column(nullable = false)
    private Long sourceId;

    @Column(nullable = false, length = 10)
    private String language;

    @Column(length = 300)
    private String translatedTitle;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String translatedBody;

    @Column(length = 1000)
    private String translatedExcerpt;

    /** 翻訳ステータス（DRAFT / IN_REVIEW / PUBLISHED / NEEDS_UPDATE）。 */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column
    private Long translatorId;

    @Column
    private Long reviewerId;

    /** 原文の updated_at スナップショット（原文更新検知に使用）。 */
    @Column(nullable = false)
    private LocalDateTime sourceUpdatedAt;

    @Column
    private LocalDateTime publishedAt;

    @Version
    private Long version;

    @Column
    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * ステータスを更新する。
     *
     * @param status 新しいステータス文字列
     */
    public void updateStatus(String status) {
        this.status = status;
    }

    /**
     * 翻訳内容を更新する。
     *
     * @param translatedTitle   翻訳タイトル
     * @param translatedBody    翻訳本文
     * @param translatedExcerpt 翻訳抜粋
     */
    public void updateContent(String translatedTitle, String translatedBody, String translatedExcerpt) {
        this.translatedTitle = translatedTitle;
        this.translatedBody = translatedBody;
        this.translatedExcerpt = translatedExcerpt;
    }

    /**
     * 公開処理を行う。ステータスをPUBLISHEDにして公開日時を記録する。
     */
    public void publish() {
        this.status = "PUBLISHED";
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * 原文の更新日時スナップショットを更新し、ステータスをNEEDS_UPDATEにする。
     *
     * @param sourceUpdatedAt 原文の更新日時
     */
    public void markNeedsUpdate(LocalDateTime sourceUpdatedAt) {
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.status = "NEEDS_UPDATE";
    }

    /**
     * レビュアーIDを設定する。
     *
     * @param reviewerId レビュアーのユーザーID
     */
    public void assignReviewer(Long reviewerId) {
        this.reviewerId = reviewerId;
    }
}
