package com.mannschaft.app.actionmemo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 行動メモタグ更新リクエスト DTO（PATCH 用）。
 *
 * <p>全フィールド任意。送信されたフィールドのみ更新する。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateActionMemoTagRequest {

    @Size(max = 50, message = "タグ名は50文字以内で入力してください")
    private String name;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "色は #RRGGBB 形式で入力してください")
    private String color;
}
