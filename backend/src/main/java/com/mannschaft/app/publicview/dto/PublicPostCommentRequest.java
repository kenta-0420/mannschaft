package com.mannschaft.app.publicview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F19.1 Phase 6-B: 公開投稿コメント投稿リクエスト DTO。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.7 Phase 6-B</p>
 */
public record PublicPostCommentRequest(
        @NotBlank(message = "コメント本文は必須です")
        @Size(max = 1000, message = "コメント本文は 1000 文字以内で入力してください")
        String content
) {
}
