package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F17.2 Wave1 ②寄合後半戦 — 宿題 TODO 作成リクエスト（設計書 §4.4）。
 *
 * <p>{@code assigneeUserId} は任意。{@code null} なら「手挙げ待ち（未割当）」として作成する。</p>
 */
public record MeetupTodoCreateRequest(
        @NotBlank @Size(max = 200) String title,
        Long assigneeUserId) {
}
