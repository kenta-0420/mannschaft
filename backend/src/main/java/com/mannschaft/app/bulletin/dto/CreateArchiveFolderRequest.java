package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * 保管庫フォルダ作成リクエストDTO（設計書 F05.1 §4 POST .../archive/folders）。
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateArchiveFolderRequest {

    /** フォルダ名（必須・1〜100文字）。 */
    @NotBlank
    @Size(max = 100)
    private String name;

    /** 親フォルダ ID（任意。NULL = 保管庫直下のルートフォルダ）。 */
    private UUID parentFolderId;

    /** カラー（任意・HEX 形式 #RRGGBB）。 */
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "color は #RRGGBB 形式で指定してください")
    private String color;

    /** アイコン（任意・PrimeIcons 名・最大40文字）。 */
    @Size(max = 40)
    private String icon;
}
