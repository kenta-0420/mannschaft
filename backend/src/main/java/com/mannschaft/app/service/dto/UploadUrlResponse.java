package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * アップロードURLレスポンス。
 */
@Getter
@Builder
public class UploadUrlResponse {

    private String uploadUrl;
    private String fileKey;
    private Integer expiresIn;
}
