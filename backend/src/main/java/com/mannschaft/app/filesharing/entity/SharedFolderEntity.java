package com.mannschaft.app.filesharing.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.filesharing.FileScopeType;
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
 * 共有フォルダエンティティ。フォルダの階層構造とスコープを管理する。
 */
@Entity
@Table(name = "shared_folders")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SharedFolderEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileScopeType scopeType;

    private Long teamId;

    private Long organizationId;

    private Long userId;

    /**
     * F08.7.1 / 04 §2.1: 大会 ID / ディビジョン ID を保持する汎用スコープ参照カラム。
     *
     * <p>{@code scopeType=TOURNAMENT} のとき tournaments.id、{@code TOURNAMENT_DIVISION} のとき
     * tournament_divisions.id を保持する。既存スコープ（TEAM/ORGANIZATION/PERSONAL）では NULL。
     * クロスドメイン FK は張らず ID 参照のみ（原則1）。</p>
     */
    private Long scopeRefId;

    private Long parentId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 500)
    private String description;

    private Long createdBy;

    /**
     * B: 最低可視ロール。{@code NULL} なら所属者全員可視（SCOPE_AFFILIATED＝従来挙動）。
     * TEAM / ORGANIZATION スコープでのみ意味を持つ（PERSONAL は所有者のみ・大会は主催組織 ORG ロールで判定）。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private FileVisibilityRole minVisibleRole;

    /**
     * C: ダウンロード禁止フラグ。{@code true} なら配下ファイルの DL URL 発行を拒否する（禁止は単調・ファイルで解除不可）。
     * <b>限界</b>: ブラウザ表示できる以上、完全な DL 防止は原理的に不可。運用上の抑止に留まる。
     */
    @Column(nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private Boolean downloadDisabled = false;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * フォルダ名を変更する。
     *
     * @param name 新しいフォルダ名
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
     * 親フォルダを変更する（移動）。
     *
     * @param parentId 新しい親フォルダID
     */
    public void moveToParent(Long parentId) {
        this.parentId = parentId;
    }

    /**
     * 最低可視ロールを変更する（B）。{@code null} で「所属者全員可視」へ戻す。
     *
     * @param minVisibleRole 新しい最低可視ロール（null 可）
     */
    public void changeMinVisibleRole(FileVisibilityRole minVisibleRole) {
        this.minVisibleRole = minVisibleRole;
    }

    /**
     * ダウンロード禁止フラグを変更する（C）。
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
