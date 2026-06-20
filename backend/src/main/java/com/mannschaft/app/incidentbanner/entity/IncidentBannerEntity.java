package com.mannschaft.app.incidentbanner.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * 障害告知バナーエンティティ。
 *
 * <p>管理者がシスアド画面から手動で公開する障害・メンテナンス告知バナーを表す。
 * バナーには多言語翻訳メッセージ（{@link IncidentBannerTranslationEntity}）を紐付けられる。</p>
 *
 * <p>created_by は users ドメインへのクロスドメインFK を張らない（アーキテクチャ原則1）。</p>
 *
 * <p>論理削除を採用。{@code @SQLRestriction("deleted_at IS NULL")} により
 * 削除済みレコードは自動的に除外される。</p>
 */
@Entity
@Table(name = "incident_banners")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class IncidentBannerEntity extends UuidV7Entity {

    /**
     * バナーのレベル（例: "INFO", "WARNING", "ERROR"）。
     */
    @Column(name = "level", nullable = false, length = 10)
    @Builder.Default
    private String level = "INFO";

    /**
     * 表示対象ページのパターン（例: "*", "/top", "/admin/*"）。
     */
    @Column(name = "page_pattern", nullable = false, length = 255)
    @Builder.Default
    private String pagePattern = "*";

    /**
     * 公開フラグ。true の場合のみユーザーに表示される。
     */
    @Column(name = "published", nullable = false)
    @Builder.Default
    private boolean published = false;

    /**
     * 翻訳メッセージの基準言語（例: "ja"）。
     * 指定言語の翻訳が存在しない場合のフォールバック言語として使用する。
     */
    @Column(name = "original_language", nullable = false, length = 10)
    @Builder.Default
    private String originalLanguage = "ja";

    /**
     * 表示開始日時。NULL の場合は期間制限なし（即時有効）。
     */
    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    /**
     * 表示終了日時。NULL の場合は期間制限なし（無期限）。
     */
    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    /**
     * 作成者のユーザーID（クロスドメインFK 張らず ID のみ保持・原則1）。
     */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 論理削除日時。NULL の場合は有効なレコード。
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * バナーを公開状態にする。
     */
    public void publish() {
        this.published = true;
    }

    /**
     * バナーを非公開状態にする。
     */
    public void unpublish() {
        this.published = false;
    }

    /**
     * バナーの表示内容を更新する。
     *
     * @param level            バナーレベル
     * @param pagePattern      表示対象ページパターン
     * @param originalLanguage 基準言語
     * @param startsAt         表示開始日時（NULL で無制限）
     * @param endsAt           表示終了日時（NULL で無制限）
     */
    public void update(String level, String pagePattern, String originalLanguage,
                       LocalDateTime startsAt, LocalDateTime endsAt) {
        this.level = level;
        this.pagePattern = pagePattern;
        this.originalLanguage = originalLanguage;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
