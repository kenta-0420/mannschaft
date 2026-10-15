package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 村ニュースレター号 コメント保存リクエスト（F17.1 ②-4・設計書 §8.1 / §4.4）。
 *
 * <p>{@code version} は楽観ロック（設計書 §4.4）に用いる。号の現在の版番号と一致しない場合は
 * 409（{@code NEWSLETTER_ISSUE_VERSION_CONFLICT}）。{@code comment} は空文字でクリア可（null 許容）。</p>
 *
 * @param comment 村長コメント本文（クリアする場合は空文字・最大 10000 文字）
 * @param version 楽観ロック版番号（必須）
 */
@Builder
public record NewsletterCommentUpdateRequest(
        @Size(max = 10000, message = "コメントは10000文字以内で入力してください")
        String comment,

        @NotNull(message = "version は必須です")
        Long version
) {}
