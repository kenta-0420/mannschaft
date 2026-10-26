package com.mannschaft.app.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 壁紙追加リクエストDTO（SYSTEM_ADMIN用）。
 */
@Getter
@RequiredArgsConstructor
public class CreateWallpaperRequest {

    @NotBlank(message = "テンプレートスラッグを入力してください")
    @Size(max = 50, message = "テンプレートスラッグは50文字以内で入力してください")
    private final String templateSlug;

    @NotBlank(message = "壁紙名を入力してください")
    @Size(max = 100, message = "壁紙名は100文字以内で入力してください")
    private final String name;

    @NotBlank(message = "画像URLを入力してください")
    @Size(max = 500, message = "画像URLは500文字以内で入力してください")
    private final String imageUrl;

    @NotBlank(message = "サムネイルURLを入力してください")
    @Size(max = 500, message = "サムネイルURLは500文字以内で入力してください")
    private final String thumbnailUrl;

    private final String category;

    private final Integer sortOrder;
}
