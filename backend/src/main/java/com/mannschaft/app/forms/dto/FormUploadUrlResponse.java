package com.mannschaft.app.forms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォーム添付 Pre-signed アップロード URL レスポンス DTO（F05.7 Phase 11 第四陣 4-B）。
 */
@Getter
@RequiredArgsConstructor
public class FormUploadUrlResponse {

    /** Pre-signed PUT URL */
    private final String uploadUrl;

    /** 確定した R2/S3 オブジェクトキー */
    private final String fileKey;

    /** 有効期限（秒） */
    private final long expiresIn;
}
