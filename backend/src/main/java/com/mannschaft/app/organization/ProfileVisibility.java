package com.mannschaft.app.organization;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * プロフィール項目ごとの公開可否フラグ。
 * JSON カラム {@code profile_visibility} にシリアライズ/デシリアライズする。
 * 未知のキーは Jackson の FAIL_ON_UNKNOWN_PROPERTIES でバリデーション（Service 層で適用）。
 */
public record ProfileVisibility(
        @JsonProperty("homepage_url") Boolean homepageUrl,
        @JsonProperty("established_date") Boolean establishedDate,
        @JsonProperty("philosophy") Boolean philosophy,
        @JsonProperty("officers") Boolean officers,
        @JsonProperty("custom_fields") Boolean customFields
) {
    /** 全項目 false（非公開）のデフォルトインスタンス */
    public static ProfileVisibility allPrivate() {
        return new ProfileVisibility(false, false, false, false, false);
    }

    /** null の場合は false として扱う */
    public boolean isHomepageUrlVisible() {
        return Boolean.TRUE.equals(homepageUrl);
    }

    public boolean isEstablishedDateVisible() {
        return Boolean.TRUE.equals(establishedDate);
    }

    public boolean isPhilosophyVisible() {
        return Boolean.TRUE.equals(philosophy);
    }

    public boolean isOfficersVisible() {
        return Boolean.TRUE.equals(officers);
    }

    public boolean isCustomFieldsVisible() {
        return Boolean.TRUE.equals(customFields);
    }
}
