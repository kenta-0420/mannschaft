package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * グローバル方式 返信作成リクエスト DTO（F17.1 村掲示板グローバル方式）。
 *
 * <p>FE は {@code POST /api/v1/bulletin/threads/{threadId}/replies} および
 * {@code POST /api/v1/bulletin/replies/{replyId}/replies}（ネスト返信）の body に {@code {body}} を送る
 * （{@code frontend/app/composables/bulletin/useBulletinReplies.ts createReply() / createNestedReply()}）。
 * 親返信 ID はスレッド直下なら無し、ネスト返信なら URL の {@code replyId} から解決するため
 * 本 DTO は本文のみを保持する。</p>
 *
 * <p>Jackson の bean バインディングに対応するため {@code @NoArgsConstructor + @Setter} とする。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class GlobalCreateReplyRequest {

    @NotBlank
    private String body;
}
