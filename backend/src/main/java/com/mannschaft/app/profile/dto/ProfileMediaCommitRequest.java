package com.mannschaft.app.profile.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * プロフィールメディアコミットリクエスト DTO。
 * クライアントが R2 アップロード完了後に r2Key を通知する。
 */
@Getter
@NoArgsConstructor
public class ProfileMediaCommitRequest {

    /** アップロード済みの R2 オブジェクトキー */
    @NotBlank
    private String r2Key;
}
