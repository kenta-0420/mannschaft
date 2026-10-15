package com.mannschaft.app.actionmemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 行動メモタグ作成リクエスト DTO。
 *
 * <p>設計書 §4 に従い、{@code name} は必須（最大50文字）、
 * {@code color} は任意（HEX 形式 {@code #RRGGBB}、省略時は NULL = デフォルト色）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateActionMemoTagRequest {

    @NotBlank(message = "タグ名を入力してください")
    @Size(max = 50, message = "タグ名は50文字以内で入力してください")
    private String name;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "色は #RRGGBB 形式で入力してください")
    private String color;
}
