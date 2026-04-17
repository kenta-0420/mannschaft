package com.mannschaft.app.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * プロフィールメディアアップロード URL 発行リクエスト DTO。
 * F01.6 プロフィールアイコン・バナー管理。
 */
@Getter
@NoArgsConstructor
public class ProfileMediaUploadUrlRequest {

    /** MIME タイプ（例: "image/jpeg", "image/png", "image/webp", "image/gif"）*/
    @NotBlank
    private String contentType;

    /** ファイルサイズ（バイト単位）*/
    @Positive
    private long fileSize;
}
