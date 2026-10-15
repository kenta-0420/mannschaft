package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F17.2 Wave1 ②寄合後半戦 — コメント投稿リクエスト（設計書 §4.4）。
 */
public record MeetupCommentCreateRequest(
        @NotBlank @Size(max = 5000) String body) {
}
