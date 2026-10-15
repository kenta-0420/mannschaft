package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * F01.9 年齢確認・保護者同意機能: 年齢区分設定更新リクエスト（管理者向け）。
 *
 * <p>{@code featuresEnabled} と {@code themeConfig} は JSON として受け取り、
 * サービス層で文字列にシリアライズして保存する。</p>
 */
@Getter
public class AgeGroupSettingsUpdateRequest {

    /** 機能有効フラグ（任意の JSON 構造）*/
    private final Object featuresEnabled;

    /** UI テーマ設定（任意の JSON 構造）*/
    private final Object themeConfig;

    @JsonCreator
    public AgeGroupSettingsUpdateRequest(
            @JsonProperty("features_enabled") Object featuresEnabled,
            @JsonProperty("theme_config") Object themeConfig) {
        this.featuresEnabled = featuresEnabled;
        this.themeConfig = themeConfig;
    }
}
