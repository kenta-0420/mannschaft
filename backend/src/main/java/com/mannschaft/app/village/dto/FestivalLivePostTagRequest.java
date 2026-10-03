package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotNull;

/**
 * F17.2 Wave2 ③お祭りの実況タグ付けリクエスト（設計書 §5.6）。
 *
 * <p>{@code timelinePostId} は既存 VILLAGE タイムライン投稿の ID（BIGINT）。
 * timeline は別ドメイン（BaseEntity・BIGINT）のため Long で受ける（原則1・ID 参照のみ）。</p>
 */
public record FestivalLivePostTagRequest(
        @NotNull Long timelinePostId) {
}
