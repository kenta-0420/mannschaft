package com.mannschaft.app.favorite.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * お気に入り追加リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class AddFavoriteRequest {

    /** エンティティ種別（"TEAM", "ORGANIZATION", "KB_PAGE", "BLOG_AUTHOR", "VILLAGE"）。 */
    @NotBlank
    private final String entityType;

    /** エンティティID（文字列形式）。 */
    @NotBlank
    private final String entityId;
}
