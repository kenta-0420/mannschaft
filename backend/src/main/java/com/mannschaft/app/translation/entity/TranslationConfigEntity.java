package com.mannschaft.app.translation.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 翻訳設定エンティティ。
 * スコープごとの翻訳設定（原文言語・有効言語・自動検出フラグ）を管理する。
 */
@Entity
@Table(
        name = "translation_configs",
        uniqueConstraints = @UniqueConstraint(name = "uq_tc_scope", columnNames = {"scope_type", "scope_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TranslationConfigEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String primaryLanguage = "ja";

    /**
     * 有効な翻訳対象言語コードのJSON文字列（例: '["en","ko"]'）。
     */
    @Column(nullable = false, columnDefinition = "JSON")
    @Builder.Default
    private String enabledLanguages = "[]";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAutoDetectReaderLanguage = false;

    @Version
    private Long version;

    /**
     * 有効言語リストを更新する。
     *
     * @param enabledLanguagesJson JSON文字列
     */
    public void updateEnabledLanguages(String enabledLanguagesJson) {
        this.enabledLanguages = enabledLanguagesJson;
    }

    /**
     * 原文言語を変更する。
     *
     * @param primaryLanguage 言語コード
     */
    public void updatePrimaryLanguage(String primaryLanguage) {
        this.primaryLanguage = primaryLanguage;
    }

    /**
     * Accept-Language自動検出フラグを変更する。
     *
     * @param autoDetect 自動検出するか
     */
    public void updateAutoDetect(boolean autoDetect) {
        this.isAutoDetectReaderLanguage = autoDetect;
    }
}
