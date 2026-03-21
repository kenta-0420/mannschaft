package com.mannschaft.app.timeline.entity;

import com.mannschaft.app.timeline.AttachmentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * タイムライン投稿添付ファイルエンティティ。画像・動画・ファイル・リンクOGPを管理する。
 */
@Entity
@Table(name = "timeline_post_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimelinePostAttachmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long timelinePostId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttachmentType attachmentType;

    @Column(length = 500)
    private String fileKey;

    @Column(length = 255)
    private String originalFilename;

    private Integer fileSize;

    @Column(length = 100)
    private String mimeType;

    private Short imageWidth;

    private Short imageHeight;

    @Column(length = 2048)
    private String videoUrl;

    @Column(length = 2048)
    private String videoThumbnailUrl;

    @Column(length = 500)
    private String videoTitle;

    @Column(length = 2048)
    private String linkUrl;

    @Column(length = 500)
    private String ogTitle;

    @Column(length = 1000)
    private String ogDescription;

    @Column(length = 2048)
    private String ogImageUrl;

    @Column(length = 200)
    private String ogSiteName;

    @Column(nullable = false)
    @Builder.Default
    private Short sortOrder = 0;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
