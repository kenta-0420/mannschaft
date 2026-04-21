package com.mannschaft.app.visibility.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * 公開範囲テンプレート更新リクエスト DTO。
 * 構造は {@link CreateVisibilityTemplateRequest} と同一。
 */
@Getter
@Builder
@Jacksonized
public class UpdateVisibilityTemplateRequest {

    /** テンプレート名（必須、1〜60文字） */
    @NotBlank(message = "name は必須です")
    @Size(min = 1, max = 60, message = "name は1〜60文字で入力してください")
    private final String name;

    /** テンプレートの説明（任意、最大240文字） */
    @Size(max = 240, message = "description は240文字以内で入力してください")
    private final String description;

    /** アイコン絵文字（任意、最大16文字） */
    @Size(max = 16, message = "iconEmoji は16文字以内で入力してください")
    private final String iconEmoji;

    /** ルール一覧（1〜20件） */
    @NotEmpty(message = "rules は1件以上必要です")
    @Size(min = 1, max = 20, message = "rules は1〜20件で指定してください")
    @Valid
    private final List<RuleRequest> rules;
}
