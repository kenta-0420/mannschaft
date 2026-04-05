package com.mannschaft.app.cms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ブログ画像アップロード管理エンティティ。
 */
@Entity
@Table(name = "blog_image_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BlogImageUploadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long blogPostId;

    private Long uploaderId;

    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    @Column(nullable = false)
    private Integer fileSize;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 記事との紐付けを設定する。
     */
    public void linkToPost(Long blogPostId) {
        this.blogPostId = blogPostId;
    }

    /**
     * 記事との紐付けを解除する。
     */
    public void unlinkFromPost() {
        this.blogPostId = null;
    }
}
