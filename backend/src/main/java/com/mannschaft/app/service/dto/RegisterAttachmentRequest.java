package com.mannschaft.app.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 添付ファイルメタデータ登録リクエスト。
 */
@Getter
@Setter
public class RegisterAttachmentRequest {

    @NotBlank
    private String fileKey;

    @NotBlank
    private String fileName;

    @NotBlank
    private String contentType;

    @NotNull
    private Long fileSize;

    private Integer sortOrder;
}
