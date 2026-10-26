package com.mannschaft.app.quickmemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * タグ作成リクエスト。
 */
public record CreateTagRequest(

        @NotBlank
        @Size(min = 1, max = 30)
        String name,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "カラーコードは #RRGGBB 形式で指定してください")
        String color
) {}
