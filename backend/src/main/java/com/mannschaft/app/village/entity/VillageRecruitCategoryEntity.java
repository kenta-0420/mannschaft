package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村ごと募集カテゴリマスタエンティティ（F17.1 P2）。
 *
 * <p>村長・長老が村の実情に合わせて募集カテゴリを自由定義する（スポーツ固着の根治。
 * 設計書 {@code docs/features/F17.1_village_headman_console_and_recruit_categories.md} §4.2）。</p>
 *
 * <p><strong>{@link com.mannschaft.app.todo.entity.TodoStatusLabelEntity} から意図的に借りない点</strong>:
 * 同エンティティは {@code assertMutable()} で SYSTEM 既定ラベルの変更・削除を禁止しているが、
 * 本エンティティは {@code isPreset} を由来の記録に留め、変更・削除の可否には一切関与させない
 * （マスターの方針「村長・長老が自由に定義できる」を体現するため。設計書 §4.2 の注）。</p>
 */
@Entity
@Table(name = "village_recruit_categories")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageRecruitCategoryEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE）。 */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** カテゴリ名（村長・長老の自由入力・i18n 対象外のユーザーデータ）。 */
    @Column(name = "name", nullable = false, length = 40)
    private String name;

    /** 補足説明（任意）。 */
    @Column(name = "description", length = 200)
    private String description;

    /** 表示色 #RRGGBB（任意）。 */
    @Column(name = "color", length = 7)
    private String color;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    /** 自動投入された既定プリセット由来かの記録のみ。変更・削除の可否には関与しない。 */
    @Column(name = "is_preset", nullable = false)
    private Boolean isPreset;

    /** プリセット識別子（移行トレーサビリティ専用・表示には使わない）。カスタムは NULL。 */
    @Column(name = "preset_key", length = 30)
    private String presetKey;

    /** 作成者ユーザーID（FK 張らない・原則1）。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.displayOrder == null) {
            this.displayOrder = 0;
        }
        if (this.isPreset == null) {
            this.isPreset = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** カテゴリ名を変更する（プリセット由来でも可変・設計書 §4.2）。 */
    public void rename(String name) {
        this.name = name;
    }

    /** 補足説明を変更する。 */
    public void redescribe(String description) {
        this.description = description;
    }

    /** 表示色を変更する。 */
    public void recolor(String color) {
        this.color = color;
    }

    /** 表示順を変更する。 */
    public void reorder(Integer displayOrder) {
        this.displayOrder = displayOrder != null ? displayOrder : 0;
    }

    /** 論理削除する（使用中でないことは呼び出し元 Service で保証済みであること）。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
