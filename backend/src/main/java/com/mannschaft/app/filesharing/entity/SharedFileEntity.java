package com.mannschaft.app.filesharing.entity;

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
 * 共有ファイルエンティティ。ファイルのメタ情報とバージョン管理を行う。
 */
@Entity
@Table(name = "shared_files")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SharedFileEntity extends BaseEntity {

    @Column(nullable = false)
    private Long folderId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 500)
    private String fileKey;

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(length = 500)
    private String description;

    private Long createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Integer currentVersion = 1;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * ファイル名を変更する。
     *
     * @param name 新しいファイル名
     */
    public void changeName(String name) {
        this.name = name;
    }

    /**
     * 説明を変更する。
     *
     * @param description 新しい説明
     */
    public void changeDescription(String description) {
        this.description = description;
    }

    /**
     * フォルダを変更する（移動）。
     *
     * @param folderId 新しいフォルダID
     */
    public void moveToFolder(Long folderId) {
        this.folderId = folderId;
    }

    /**
     * 新しいバージョンをアップロードした際にファイル情報を更新する。
     *
     * @param fileKey     新しいファイルキー
     * @param fileSize    新しいファイルサイズ
     * @param contentType 新しいコンテンツタイプ
     * @param versionNumber 新しいバージョン番号
     */
    public void updateToNewVersion(String fileKey, Long fileSize, String contentType, Integer versionNumber) {
        this.fileKey = fileKey;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.currentVersion = versionNumber;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
