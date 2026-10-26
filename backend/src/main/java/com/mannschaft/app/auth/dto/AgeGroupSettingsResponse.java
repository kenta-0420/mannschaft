package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.entity.AgeGroupSettingsEntity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.9 年齢確認・保護者同意機能: 年齢区分設定レスポンス。
 *
 * <p>{@code featuresEnabled} と {@code themeConfig} は JSON 文字列のまま保持し、
 * クライアント側でパースする。</p>
 */
@Getter
@RequiredArgsConstructor
public class AgeGroupSettingsResponse {

    /** 年齢グループ識別子（自然キー）*/
    private final String ageGroup;

    /** 年齢グループの表示名 */
    private final String displayName;

    /** 最小年齢（歳）*/
    private final int minAge;

    /**
     * 最大年齢（歳）。
     * 成人（ADULT）などの上限なしグループは null。
     */
    private final Integer maxAge;

    /** 機能有効フラグ（JSON 文字列）*/
    private final String featuresEnabled;

    /** UI テーマ設定（JSON 文字列）*/
    private final String themeConfig;

    /**
     * {@link AgeGroupSettingsEntity} から {@link AgeGroupSettingsResponse} を生成するファクトリメソッド。
     */
    public static AgeGroupSettingsResponse from(AgeGroupSettingsEntity entity) {
        return new AgeGroupSettingsResponse(
                entity.getAgeGroup(),
                entity.getDisplayName(),
                entity.getMinAge(),
                entity.getMaxAge(),
                entity.getFeaturesEnabled(),
                entity.getThemeConfig()
        );
    }
}
