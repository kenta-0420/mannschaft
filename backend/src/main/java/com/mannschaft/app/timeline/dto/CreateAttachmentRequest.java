package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムライン投稿添付ファイル作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateAttachmentRequest {

    @NotBlank
    private final String attachmentType;

    private final String fileKey;

    private final String originalFilename;

    private final Integer fileSize;

    private final String mimeType;

    private final Short imageWidth;

    private final Short imageHeight;

    private final String videoUrl;

    private final String videoThumbnailUrl;

    private final String videoTitle;

    private final String linkUrl;

    private final String ogTitle;

    private final String ogDescription;

    private final String ogImageUrl;

    private final String ogSiteName;

    private final Short sortOrder;
}
