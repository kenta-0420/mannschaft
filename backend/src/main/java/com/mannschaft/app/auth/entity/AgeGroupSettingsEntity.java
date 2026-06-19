package com.mannschaft.app.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F01.9 年齢確認・保護者同意機能: 年齢グループ設定マスタエンティティ。
 *
 * <p>年齢グループ（{@link com.mannschaft.app.auth.AgeGroup} の文字列値）ごとの
 * 機能制限・テーマ設定を管理するマスタテーブルに対応する。</p>
 *
 * <h2>設計上の注意（マスタテーブル例外）</h2>
 * <p>本テーブルは全テナント共通の参照データであり、書き込みは運用バッチのみ行う。
 * CLAUDE.md 原則 6 の「マスタテーブル例外」に該当するため、
 * {@code UuidV7Entity} を継承せず自然キー（age_group 文字列）を使用する。</p>
 *
 * <p>{@code featuresEnabled} / {@code themeConfig} は JSON 文字列として保持し、
 * パース・シリアライズはサービス層で行う（Entity は生の JSON 文字列のまま保持）。</p>
 */
@Entity
@Table(name = "age_group_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class AgeGroupSettingsEntity {

    /**
     * 年齢グループ識別子（自然キー）。
     * {@link com.mannschaft.app.auth.AgeGroup} の name() 値に一致する。
     */
    @Id
    @Column(name = "age_group", nullable = false, length = 30)
    private String ageGroup;

    /** 年齢グループの表示名（UI で表示するラベル）*/
    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    /** 年齢グループの最小年齢（歳）*/
    @Column(name = "min_age", nullable = false)
    private Integer minAge;

    /**
     * 年齢グループの最大年齢（歳）。
     * 成人（ADULT）などの上限なしグループは NULL とする。
     */
    @Column(name = "max_age")
    private Integer maxAge;

    /**
     * 機能有効フラグの JSON 文字列。
     * 例: {"chat":true,"advertising":false}
     * デフォルト値は空オブジェクト "{}"。
     */
    @Builder.Default
    @Column(name = "features_enabled", columnDefinition = "JSON", nullable = false)
    private String featuresEnabled = "{}";

    /**
     * UI テーマ設定の JSON 文字列。
     * 例: {"primaryColor":"#4CAF50","iconSet":"kids"}
     * デフォルト値は空オブジェクト "{}"。
     */
    @Builder.Default
    @Column(name = "theme_config", columnDefinition = "JSON", nullable = false)
    private String themeConfig = "{}";

    /** 最終更新者のユーザー ID（運用バッチ実行者）*/
    @Column(name = "updated_by")
    private Long updatedBy;

    /** レコード最終更新日時 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // -------------------------------------------------------------------
    // ビジネスメソッド
    // -------------------------------------------------------------------

    /**
     * 機能有効フラグと UI テーマ設定を更新する（管理者操作）。
     *
     * @param featuresEnabledJson 機能有効フラグの JSON 文字列
     * @param themeConfigJson     UI テーマ設定の JSON 文字列
     */
    public void update(String featuresEnabledJson, String themeConfigJson) {
        if (featuresEnabledJson != null) {
            this.featuresEnabled = featuresEnabledJson;
        }
        if (themeConfigJson != null) {
            this.themeConfig = themeConfigJson;
        }
    }

    // -------------------------------------------------------------------
    // ライフサイクルコールバック
    // -------------------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
