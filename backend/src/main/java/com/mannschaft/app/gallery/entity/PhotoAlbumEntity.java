package com.mannschaft.app.gallery.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gallery.AlbumVisibility;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 写真アルバムエンティティ。アルバムのメタ情報・閲覧権限・写真数を管理する。
 */
@Entity
@Table(name = "photo_albums")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PhotoAlbumEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String description;

    private Long coverPhotoId;

    private LocalDate eventDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private AlbumVisibility visibility = AlbumVisibility.ALL_MEMBERS;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allowMemberUpload = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allowDownload = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer photoCount = 0;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * アルバム情報を更新する。
     */
    public void update(String title, String description, LocalDate eventDate,
                       AlbumVisibility visibility, Boolean allowMemberUpload,
                       Boolean allowDownload, Long coverPhotoId) {
        this.title = title;
        this.description = description;
        this.eventDate = eventDate;
        this.visibility = visibility;
        this.allowMemberUpload = allowMemberUpload;
        this.allowDownload = allowDownload;
        this.coverPhotoId = coverPhotoId;
    }

    /**
     * 写真カウントを加算する。
     */
    public void incrementPhotoCount(int count) {
        this.photoCount += count;
    }

    /**
     * 写真カウントを減算する。
     */
    public void decrementPhotoCount() {
        if (this.photoCount > 0) {
            this.photoCount--;
        }
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
