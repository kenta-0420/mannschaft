package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * 広告クリエイティブ作成リクエスト（F09.19.1）。
 */
public record CreateAdCreativeRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @URL
        @Size(max = 500)
        String imageUrl,

        @NotBlank
        @URL
        @Size(max = 500)
        String destinationUrl,

        // ─── F09.19.1 拡張。placement は必須（欠落 400）。width/height/altText は DB 制約整合の範囲検証 ───

        /** 掲載面。必須（F09.19 §5.2 V144.001）。 */
        @NotNull
        com.mannschaft.app.advertising.AdPlacement placement,

        /** バナー幅 px（任意・SMALLINT UNSIGNED 範囲 0〜65535）。 */
        @Min(0)
        @Max(65535)
        Integer width,

        /** バナー高さ px（任意・SMALLINT UNSIGNED 範囲 0〜65535）。 */
        @Min(0)
        @Max(65535)
        Integer height,

        /** 代替テキスト（任意・最大 200 文字。DB VARCHAR(200) 整合）。 */
        @Size(max = 200)
        String altText
) {
}
