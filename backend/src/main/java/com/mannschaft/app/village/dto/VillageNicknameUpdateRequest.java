package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F17.1 B4 — 村ニックネーム更新リクエスト。
 *
 * <p>Phase 1 は全村共通 1 つ運用なので village_id は受け取らない。</p>
 *
 * @param nickname    村ニックネーム（2〜40 文字、プラットフォーム全体で一意）
 * @param avatarR2Key R2 アバターキー（任意、255 文字以内）
 * @param bio         自己紹介文（任意、500 文字以内）
 */
public record VillageNicknameUpdateRequest(
        @NotBlank(message = "ニックネームを入力してください")
        @Size(min = 2, max = 40, message = "ニックネームは2〜40文字で入力してください")
        String nickname,

        @Size(max = 255, message = "アバターキーは255文字以内で指定してください")
        String avatarR2Key,

        @Size(max = 500, message = "自己紹介は500文字以内で入力してください")
        String bio
) {}
