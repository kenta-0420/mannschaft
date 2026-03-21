package com.mannschaft.app.gallery.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 個別写真エンティティ。写真のメタ情報・S3キー・EXIF情報を管理する。
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

    @Column(nullable = false, length = 500)
    private String s3Key;

    @Column(length = 500)
    private String thumbnailS3Key;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    private Integer fileSize;

    private Integer width;

    private Integer height;

    @Column(length = 300)
    private String caption;

    private LocalDateTime takenAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    private Long uploadedBy;

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
    public void updateThumbnailAndExif(String thumbnailS3Key, Integer width, Integer height,
                                        LocalDateTime takenAt, String contentType) {
        this.thumbnailS3Key = thumbnailS3Key;
        this.width = width;
        this.height = height;
        this.takenAt = takenAt;
        this.contentType = contentType;
    }
}
