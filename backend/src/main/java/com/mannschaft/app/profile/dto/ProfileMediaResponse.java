package com.mannschaft.app.profile.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * プロフィールメディアレスポンス DTO。
 * コミット完了後に返す署名付き URL を含む。
 */
@Getter
@Builder
public class ProfileMediaResponse {

    /** メディアロール（"icon" または "banner"）*/
    private String mediaRole;

    /** 署名付き表示 URL */
    private String url;
}
