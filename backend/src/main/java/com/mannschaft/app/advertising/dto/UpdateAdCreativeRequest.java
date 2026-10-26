package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * 広告クリエイティブ更新リクエスト（F09.19.1）。
 * 全フィールドは Optional（null の場合は更新しない）。範囲・文字数は非 null 時のみ検証される。
 */
public record UpdateAdCreativeRequest(

        @Size(max = 200)
        String title,

        @URL
        @Size(max = 500)
        String imageUrl,

        @URL
        @Size(max = 500)
        String destinationUrl,

        // ─── F09.19.1 拡張。null の場合は更新しない。width/height/altText は DB 制約整合の範囲検証 ───

        /** 掲載面（任意。null は変更なし）。 */
        com.mannschaft.app.advertising.AdPlacement placement,

        /** バナー幅 px（SMALLINT UNSIGNED 範囲 0〜65535）。 */
        @Min(0)
        @Max(65535)
        Integer width,

        /** バナー高さ px（SMALLINT UNSIGNED 範囲 0〜65535）。 */
        @Min(0)
        @Max(65535)
        Integer height,

        /** 代替テキスト（最大 200 文字。DB VARCHAR(200) 整合）。 */
        @Size(max = 200)
        String altText
) {
}
