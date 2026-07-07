package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * 広告クリエイティブ作成リクエスト。
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

        // ─── F09.19.1 拡張（骨格）。placement は必須（欠落 400）— バリデーションは出陣で付与 ───

        /** 掲載面。必須（F09.19 §5.2 V144.001）。 */
        com.mannschaft.app.advertising.AdPlacement placement,

        /** バナー幅 px（任意）。 */
        Integer width,

        /** バナー高さ px（任意）。 */
        Integer height,

        /** 代替テキスト（任意・最大 200 文字）。 */
        String altText
) {
}
