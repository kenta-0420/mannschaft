package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * 村ニュースレター号 タグ付け更新リクエスト（F17.1 ②-4・設計書 §8.1）。
 *
 * <p>号に付けるタグ ID の完全集合を渡す（差分ではなく置き換え）。空リストで全解除。
 * {@code version} は楽観ロック（設計書 §4.4）に用いる。</p>
 *
 * @param tagIds 付与するタグ ID の集合（必須・空リスト可）
 * @param version 楽観ロック版番号（必須）
 */
@Builder
public record NewsletterIssueTagsUpdateRequest(
        @NotNull(message = "tagIds は必須です")
        List<UUID> tagIds,

        @NotNull(message = "version は必須です")
        Long version
) {}
