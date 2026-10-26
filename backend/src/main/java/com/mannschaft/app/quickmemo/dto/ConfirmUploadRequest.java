package com.mannschaft.app.quickmemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 画像アップロード確認リクエスト。
 */
public record ConfirmUploadRequest(

        @NotBlank
        String s3Key,

        @Size(max = 255)
        String originalFilename
) {}
