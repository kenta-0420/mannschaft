package com.mannschaft.app.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/** F03.16 メンション候補ユーザー（設計書 §4.4）。 */
@Getter
@Builder
public class MentionCandidateResponse {
    private final Long userId;
    private final String displayName;

    /** {@link CommentAuthorResponse#avatarUrl} と同様、未設定のユーザーは {@code null}。 */
    @Schema(nullable = true, description = "アバター画像URL。未設定のユーザーは null")
    private final String avatarUrl;
}
