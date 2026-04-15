package com.mannschaft.app.social.entity;

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
 * フレンドフォルダエンティティ。自チーム視点のみの非対称フォルダ。
 *
 * <p>
 * A チームが B チームを「系列校」フォルダに入れていても、
 * B チームの A に対する分類はまったく別の構造となる（非対称性）。
 * </p>
 *
 * <p>
 * <b>論理削除</b>: {@code deletedAt} 設定により論理削除。90 日経過後に物理削除される（運用）。
 * </p>
 *
 * <p>
 * <b>上限</b>: 1チームあたり最大 20 フォルダ（アプリ層で検証）。
 * </p>
 */
@Entity
@Table(name = "team_friend_folders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamFriendFolderEntity extends BaseEntity {

    /** このフォルダを所有するチーム（= 自チーム）の ID */
    @Column(name = "team_id", nullable = false)
    private Long ownerTeamId;

    /** フォルダ名（例: '系列校', '姉妹団体', '練習試合候補'） */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** フォルダの説明（任意） */
    @Column(name = "description", length = 300)
    private String description;

    /** 表示色（HEX。UI バッジ色として使用） */
    @Column(name = "color", nullable = false, length = 7)
    @Builder.Default
    private String color = "#6B7280";

    /** デフォルトフォルダ判定フラグ（システム自動生成のフォルダかどうか） */
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    /** 並び替え順（小さいほど上に表示） */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer folderOrder = 0;

    /** 論理削除日時。NULL の場合はアクティブ。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * フォルダ情報を更新する。
     *
     * @param name        フォルダ名（null の場合は更新しない）
     * @param description 説明（null の場合は更新しない）
     * @param color       表示色（null の場合は更新しない）
     * @param folderOrder 並び替え順（null の場合は更新しない）
     */
    public void updateFolder(String name, String description, String color, Integer folderOrder) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (color != null) {
            this.color = color;
        }
        if (folderOrder != null) {
            this.folderOrder = folderOrder;
        }
    }

    /**
     * 論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 論理削除を解除する。
     */
    public void restore() {
        this.deletedAt = null;
    }
}
