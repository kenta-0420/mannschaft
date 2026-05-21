package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * 広告クリエイティブ更新リクエスト。
 * 全フィールドは Optional（null の場合は更新しない）。
 */
public record UpdateAdCreativeRequest(

        @Size(max = 200)
        String title,

        @URL
        @Size(max = 500)
        String imageUrl,

        @URL
        @Size(max = 500)
        String destinationUrl
) {
}
