package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.advertising.AdPlacement;
import com.mannschaft.app.advertising.AffiliateProvider;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * アフィリエイト広告設定エンティティ。
 */
@Entity
@Table(name = "affiliate_configs")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class AffiliateConfigEntity extends BaseEntity {

    /**
     * リリース前提シード（Flyway V149.20260710004057）が投入する未設定プレースホルダ tag_id の接頭辞。
     * SYSTEM_ADMIN が affiliate-settings 画面で本物の tag_id へ上書きするまでの仮値であり、
     * この接頭辞を持つ行は広告候補から除外する（CMP-260918-0025）。
     */
    public static final String PLACEHOLDER_TAG_PREFIX = "PLACEHOLDER_";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AffiliateProvider provider;

    @Column(nullable = false, length = 100)
    private String tagId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AdPlacement placement;

    @Column(length = 200)
    private String description;

    @Column(length = 500)
    private String bannerImageUrl;

    private Short bannerWidth;

    private Short bannerHeight;

    @Column(length = 200)
    private String altText;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    private LocalDateTime activeFrom;

    private LocalDateTime activeUntil;

    @Column(nullable = false)
    @Builder.Default
    private Short displayPriority = 0;

    @Column(length = 30)
    private String targetTemplate;

    @Column(length = 20)
    private String targetPrefecture;

    @Column(length = 10)
    private String targetLocale;

    private LocalDateTime deletedAt;

    /**
     * 広告設定を更新する。
     */
    public void update(AffiliateProvider provider, String tagId, AdPlacement placement,
                       String description, String bannerImageUrl, Short bannerWidth,
                       Short bannerHeight, String altText, LocalDateTime activeFrom,
                       LocalDateTime activeUntil, Short displayPriority,
                       String targetTemplate, String targetPrefecture, String targetLocale) {
        this.provider = provider;
        this.tagId = tagId;
        this.placement = placement;
        this.description = description;
        this.bannerImageUrl = bannerImageUrl;
        this.bannerWidth = bannerWidth;
        this.bannerHeight = bannerHeight;
        this.altText = altText;
        this.activeFrom = activeFrom;
        this.activeUntil = activeUntil;
        this.displayPriority = displayPriority;
        this.targetTemplate = targetTemplate;
        this.targetPrefecture = targetPrefecture;
        this.targetLocale = targetLocale;
    }

    /**
     * 有効/無効を切り替える。
     */
    public void toggleActive() {
        this.isActive = !this.isActive;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * tag_id が未設定（プレースホルダ）かどうかを判定する。
     * 広告候補選定・管理画面の警告表示など全経路から本メソッドを使う（比較ロジックの散逸防止）。
     */
    public static boolean isPlaceholderTagId(String tagId) {
        return tagId != null && tagId.startsWith(PLACEHOLDER_TAG_PREFIX);
    }

    /**
     * このエンティティの tag_id が未設定（プレースホルダ）かどうかを判定する。
     */
    public boolean isPlaceholderTagId() {
        return isPlaceholderTagId(this.tagId);
    }
}
