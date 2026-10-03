package com.mannschaft.app.bulletin.entity;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 掲示板 保管庫（アーカイブ）フォルダエンティティ（設計書 F05.1 §3）。
 *
 * <p>スコープ（チーム/組織）全員で共有する保管庫フォルダ。管理者（ADMIN/DEPUTY_ADMIN）が整理し、
 * ネスト（多階層・最大5階層 = depth 0〜4）に対応する。隣接リスト + {@code depth} カラム方式。</p>
 *
 * <p>CLAUDE.md 原則6 に従い主キーは UUIDv7（{@link UuidV7Entity} 継承）。
 * 論理削除（{@code deleted_at}）は {@code @SQLRestriction} で透過的にフィルタする
 * （既存 bulletin Entity と同方針）。</p>
 *
 * <p>FK は {@code created_by → users}（ON DELETE SET NULL）のみ。{@code scope_*} と
 * {@code parent_folder_id} は bulletin 慣習で FK を張らず、参照整合性はアプリ層で保証する。</p>
 */
@Entity
@Table(name = "bulletin_archive_folders")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BulletinArchiveFolderEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** 村スコープ時の村 ID（将来対応用。当面 UI は team/org のみ）。FK なし（原則1）。 */
    @Column(name = "scope_village_id", columnDefinition = "BINARY(16)")
    private UUID scopeVillageId;

    /** 親フォルダ（自己参照ネスト）。NULL = 保管庫直下のルートフォルダ。FK なし（bulletin 慣習）。 */
    @Column(name = "parent_folder_id", columnDefinition = "BINARY(16)")
    private UUID parentFolderId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** フォルダカラー（HEX 形式 #FF5733）。 */
    @Column(name = "color", length = 7)
    private String color;

    /** アイコン（PrimeIcons 名 例 pi-folder）。 */
    @Column(name = "icon", length = 40)
    private String icon;

    /** 階層の深さ。ルート = 0、最大4（= 5 階層）。 */
    @Column(name = "depth", nullable = false)
    @Builder.Default
    private Integer depth = 0;

    /** 同一親の中での表示順。 */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    /** 作成者 user_id（FK → users ON DELETE SET NULL）。退会で NULL になる。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.depth == null) {
            this.depth = 0;
        }
        if (this.displayOrder == null) {
            this.displayOrder = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 名前・色・アイコン・表示順を更新する（PUT の編集系）。null の引数は「変更しない」を意味する。
     *
     * @param name         フォルダ名（null = 変更しない）
     * @param color        カラー（null = 変更しない）
     * @param icon         アイコン（null = 変更しない）
     * @param displayOrder 表示順（null = 変更しない）
     */
    public void updateMeta(String name, String color, String icon, Integer displayOrder) {
        if (name != null) {
            this.name = name;
        }
        if (color != null) {
            this.color = color;
        }
        if (icon != null) {
            this.icon = icon;
        }
        if (displayOrder != null) {
            this.displayOrder = displayOrder;
        }
    }

    /**
     * 親フォルダと深さを変更する（フォルダ移動・サブツリー depth 再計算）。
     *
     * @param parentFolderId 新しい親フォルダ ID（NULL = ルートへ移動）
     * @param depth          新しい深さ
     */
    public void moveTo(UUID parentFolderId, int depth) {
        this.parentFolderId = parentFolderId;
        this.depth = depth;
    }

    /**
     * 深さのみ再設定する（サブツリーの繰り上げ・移動に伴う再採番）。
     */
    public void setDepthValue(int depth) {
        this.depth = depth;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
