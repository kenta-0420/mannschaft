package com.mannschaft.app.scopefolder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * フォルダ作成リクエストDTO。
 *
 * <p>F15.3 で {@code icon} を追加（任意）。アイコンは PrimeIcons の
 * {@code pi-xxx} 形式のみ受領（設計書 §9.8）。</p>
 */
public record CreateFolderRequest(
        @NotBlank @Size(max = 100) String name,
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "カラーコードは #RRGGBB 形式で指定してください") String color,
        @Size(max = 40)
        @Pattern(regexp = "^pi-[a-z0-9-]+$", message = "アイコン名は PrimeIcons の pi-xxx 形式で指定してください")
        String icon
) {
}
