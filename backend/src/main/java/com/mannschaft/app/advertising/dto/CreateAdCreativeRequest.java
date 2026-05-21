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
        String destinationUrl
) {
}
