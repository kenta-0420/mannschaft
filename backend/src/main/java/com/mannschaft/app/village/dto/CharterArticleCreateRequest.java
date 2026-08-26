package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 条を末尾に追加するリクエスト（{@code POST .../charter/articles}・F17.3・設計書 §18.2）。
 *
 * <p><b>version は同送しない</b>（悲観ロック直列化で末尾追加は常に成功・409 なし・§4.5/§6.3）。
 * {@code body} 空は {@code @NotBlank}、上限 2000 字は {@code @Size} で 400 に弾く（AC-08b）。</p>
 */
public record CharterArticleCreateRequest(

        @Schema(description = "条文（必須・最大2000字）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 2000) String body,

        @Schema(description = "付則（任意・最大2000字）")
        @Size(max = 2000) String supplement
) {
}
