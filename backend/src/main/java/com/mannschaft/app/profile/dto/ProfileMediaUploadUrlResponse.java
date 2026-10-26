package com.mannschaft.app.profile.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * プロフィールメディアアップロード URL 発行レスポンス DTO。
 */
@Getter
@Builder
public class ProfileMediaUploadUrlResponse {

    /** R2 オブジェクトキー */
    private String r2Key;

    /** Presigned PUT URL */
    private String uploadUrl;

    /** Presigned URL 有効秒数 */
    private int expiresIn;
}
