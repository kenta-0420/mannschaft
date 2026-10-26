package com.mannschaft.app.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * アップロードURL発行リクエスト。
 */
@Getter
@Setter
public class UploadUrlRequest {

    @NotBlank
    private String fileName;

    @NotBlank
    private String contentType;

    @NotNull
    private Long fileSize;
}
