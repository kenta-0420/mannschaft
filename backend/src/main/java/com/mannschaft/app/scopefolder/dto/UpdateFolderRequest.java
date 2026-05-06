package com.mannschaft.app.scopefolder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * フォルダ更新リクエストDTO。
 */
public record UpdateFolderRequest(
        @NotBlank @Size(max = 100) String name,
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "カラーコードは #RRGGBB 形式で指定してください") String color
) {
}
