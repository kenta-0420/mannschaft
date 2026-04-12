package com.mannschaft.app.gallery.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gallery.GalleryMediaType;
import com.mannschaft.app.gallery.GalleryProcessingStatus;
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

import java.time.LocalDateTime;

/**
 * 個別メディア（写真・動画）エンティティ。メタ情報・R2キー・EXIF情報を管理する。
 */
@Entity
@Table(name = "photos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PhotoEntity extends BaseEntity {

    @Column(nullable = false)
    private Long albumId;

    @Column(name = "r2_key", nullable = false, length = 500)
    private String r2Key;

    @Column(name = "thumbnail_r2_key", length = 500)
    private String thumbnailR2Key;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    private Long fileSize;

    private Integer width;

    private Integer height;

    @Column(length = 300)
    private String caption;

    private LocalDateTime takenAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    private Long uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private GalleryMediaType mediaType = GalleryMediaType.PHOTO;

    private Integer durationSeconds;

    @Column(length = 30)
    private String videoCodec;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private GalleryProcessingStatus processingStatus = GalleryProcessingStatus.READY;

    /**
     * 写真情報を更新する（キャプション・表示順）。
     */
    public void update(String caption, Integer sortOrder) {
        this.caption = caption;
        this.sortOrder = sortOrder;
    }

    /**
     * サムネイルとEXIF情報を更新する（非同期ジョブ用）。
     */
    public void updateThumbnailAndExif(String thumbnailR2Key, Integer width, Integer height,
                                        LocalDateTime takenAt, String contentType) {
        this.thumbnailR2Key = thumbnailR2Key;
        this.width = width;
        this.height = height;
        this.takenAt = takenAt;
        this.contentType = contentType;
    }
}
