package com.mannschaft.app.social.announcement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mannschaft.app.bulletin.dto.AttachmentResponse;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** F02.6 本文プレビュー。LOCKED の参照・本文は省略せず明示 null にする。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AnnouncementPreviewResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long feedId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AnnouncementScopeType scopeType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long scopeId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"FULL", "LOCKED"}) String accessState,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) AnnouncementSourceType sourceType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Long sourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String sourceUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BlogPostResponse blogPost,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ThreadResponse bulletinThread,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AttachmentResponse> attachments) {

    public AnnouncementPreviewResponse {
        attachments = List.copyOf(attachments);
    }

    public static AnnouncementPreviewResponse locked(Long feedId, AnnouncementScopeType scopeType, Long scopeId) {
        return new AnnouncementPreviewResponse(feedId, scopeType, scopeId, "LOCKED",
                null, null, null, null, null, List.of());
    }
}
