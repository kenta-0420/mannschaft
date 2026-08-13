package com.mannschaft.app.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F03.16 予定コメントの投稿者情報（設計書 §4.2）。
 *
 * <p>退会・匿名化済みユーザーのコメントは {@code author} 自体が {@code null} で返る
 * （本クラスのインスタンスが作られない）。</p>
 */
@Getter
@Builder
public class CommentAuthorResponse {
    private final Long userId;
    private final String displayName;

    /**
     * {@code common.NameResolverService#resolveUserAvatarUrls} は avatarUrl 未設定／解決不能の
     * ユーザーを Map に含めないため（{@code ScheduleCommentService#loadAuthors}）、その場合
     * ここは {@code null} になる（{@code auth.UserEntity#avatarUrl} 自体も DB 上 nullable）。
     */
    @Schema(nullable = true, description = "アバター画像URL。未設定のユーザーは null")
    private final String avatarUrl;
}
