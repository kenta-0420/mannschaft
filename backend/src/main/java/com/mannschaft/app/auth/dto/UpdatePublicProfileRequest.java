package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F19.1 Phase 6: プロフィール公開設定更新リクエスト。
 *
 * <p>{@code PATCH /api/v1/users/me/public-profile} で使用する。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdatePublicProfileRequest {

    /**
     * プロフィール公開フラグ。true にすると未ログインユーザーも
     * {@code GET /api/v1/public/users/{userId}} でプロフィールを閲覧できる。
     */
    @NotNull(message = "publicProfileEnabled は必須です")
    private final Boolean publicProfileEnabled;
}
