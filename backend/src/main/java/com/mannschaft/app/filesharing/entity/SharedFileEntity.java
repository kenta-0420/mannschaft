package com.mannschaft.app.filesharing.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.filesharing.FileVisibilityRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
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

    /**
     * B: ファイル個別の最低可視ロール。{@code NULL} ならフォルダ値を継承（フォルダも NULL なら所属者全員可視）。
     * ファイル経路（詳細取得 / DL URL 発行）は「ファイル値優先 → フォルダ継承」で評価する。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private FileVisibilityRole minVisibleRole;

    /**
     * C: ファイル個別のダウンロード禁止フラグ。
     * 実効禁止 = フォルダ.downloadDisabled OR ファイル.downloadDisabled（禁止は単調・ファイルで解除不可）。
     */
    @Column(nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private Boolean downloadDisabled = false;

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
     * ファイル個別の最低可視ロールを変更する（B）。{@code null} でフォルダ継承へ戻す。
     *
     * @param minVisibleRole 新しい最低可視ロール（null 可）
     */
    public void changeMinVisibleRole(FileVisibilityRole minVisibleRole) {
        this.minVisibleRole = minVisibleRole;
    }

    /**
     * ファイル個別のダウンロード禁止フラグを変更する（C）。
     *
     * @param downloadDisabled 新しい DL 禁止フラグ
     */
    public void changeDownloadDisabled(boolean downloadDisabled) {
        this.downloadDisabled = downloadDisabled;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
