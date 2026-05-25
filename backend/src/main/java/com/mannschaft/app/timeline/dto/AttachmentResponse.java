package com.mannschaft.app.timeline.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * タイムライン投稿添付ファイルレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class AttachmentResponse {

    private final Long id;
    private final String attachmentType;
    private final AttachmentFileDto file;
    private final AttachmentImageDto image;
    private final AttachmentVideoDto video;
    private final AttachmentLinkDto link;
    private final Short sortOrder;

    public record AttachmentFileDto(String fileKey, String originalFilename, Long fileSize, String mimeType) {}

    public record AttachmentImageDto(Short imageWidth, Short imageHeight) {}

    public record AttachmentVideoDto(String videoUrl, String videoThumbnailUrl, String videoTitle,
                                     String videoThumbnailKey, Integer videoDurationSeconds,
                                     String videoCodec, Short videoWidth, Short videoHeight,
                                     String videoProcessingStatus) {}

    public record AttachmentLinkDto(String linkUrl, String ogTitle, String ogDescription,
                                    String ogImageUrl, String ogSiteName) {}
}
