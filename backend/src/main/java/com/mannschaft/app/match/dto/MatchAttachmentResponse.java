package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.entity.MatchAttachmentEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 局面写真添付のレスポンス DTO（01 §B.7 / 03 §C.7a）。
 *
 * <p><b>生 fileKey は返さない</b>（ダウンロードは短命 presigned URL 経由・03 §C.7a）。</p>
 */
@Schema(name = "MatchRecordAttachmentResponse")
@Getter
@Builder
public class MatchAttachmentResponse {

    private final UUID id;
    private final UUID matchId;
    private final String originalFilename;
    private final String contentType;
    private final Long fileSize;
    private final Long createdBy;
    private final LocalDateTime createdAt;

    public static MatchAttachmentResponse from(MatchAttachmentEntity a) {
        return MatchAttachmentResponse.builder()
                .id(a.getId())
                .matchId(a.getMatchId())
                .originalFilename(a.getOriginalFilename())
                .contentType(a.getContentType())
                .fileSize(a.getFileSize())
                .createdBy(a.getCreatedBy())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
