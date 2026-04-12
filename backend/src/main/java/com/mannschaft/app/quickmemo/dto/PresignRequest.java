package com.mannschaft.app.quickmemo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Presigned URL 発行リクエスト。
 */
public record PresignRequest(

        @NotNull
        @Min(1)
        @Max(10485760)
        Integer declaredSize,

        @NotBlank
        @Pattern(regexp = "^image/(jpeg|png|webp|gif)$", message = "許可されているMIMEタイプは image/jpeg, image/png, image/webp, image/gif のみです")
        String contentType
) {}
