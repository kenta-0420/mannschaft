package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 添付ファイルレスポンス。
 */
@Getter
@Builder
public class AttachmentResponse {

    private Long id;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private Integer sortOrder;
    private String downloadUrl;
    private LocalDateTime createdAt;
}
