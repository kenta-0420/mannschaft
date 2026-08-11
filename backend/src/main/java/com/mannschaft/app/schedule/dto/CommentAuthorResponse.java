package com.mannschaft.app.schedule.dto;

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
    private final String avatarUrl;
}
