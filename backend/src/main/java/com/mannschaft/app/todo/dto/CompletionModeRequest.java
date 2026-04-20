package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * マイルストーン完了判定モード変更リクエスト（F02.7）。
 *
 * <p>AUTO / MANUAL の切り替え。AUTO は紐付く全 TODO 完了で自動達成、MANUAL は管理者の手動操作で達成。</p>
 */
public record CompletionModeRequest(
        @NotBlank
        @Pattern(regexp = "^(AUTO|MANUAL)$", message = "completion_mode は AUTO または MANUAL のみ指定可能です")
        String completionMode
) {}
