package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 村ニュースレタータグ 作成リクエスト（F17.1 ②-4・設計書 §4.7 / §8.1）。
 *
 * @param name      タグ名（必須・50 文字以内）
 * @param color     表示色 #RRGGBB（任意・未指定は既定色）
 * @param sortOrder 表示順（任意・未指定は 0）
 */
@Builder
public record NewsletterTagCreateRequest(
        @NotBlank(message = "タグ名は必須です")
        @Size(max = 50, message = "タグ名は50文字以内で入力してください")
        String name,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "色は #RRGGBB 形式で指定してください")
        String color,

        Integer sortOrder
) {}
