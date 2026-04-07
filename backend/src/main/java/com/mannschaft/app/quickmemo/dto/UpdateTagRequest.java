package com.mannschaft.app.quickmemo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * タグ更新リクエスト（すべてのフィールドはオプショナル）。
 */
public record UpdateTagRequest(

        @Size(min = 1, max = 30)
        String name,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "カラーコードは #RRGGBB 形式で指定してください")
        String color
) {}
