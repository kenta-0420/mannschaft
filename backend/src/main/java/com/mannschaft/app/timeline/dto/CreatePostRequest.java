package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * タイムライン投稿作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreatePostRequest {

    @NotBlank
    @Size(max = 5000)
    private final String content;

    private final String scopeType;

    private final Long scopeId;

    private final String postedAsType;

    private final Long postedAsId;

    private final Long parentId;

    private final Long repostOfId;

    private final LocalDateTime scheduledAt;

    private final CreatePollRequest poll;

    @Size(max = 10)
    private final List<CreateAttachmentRequest> attachments;

    /**
     * scopeType のデフォルト値を返す。null の場合は PUBLIC を返す。
     */
    public String getScopeTypeOrDefault() {
        return scopeType != null ? scopeType : "PUBLIC";
    }

    /**
     * scopeId のデフォルト値を返す。null の場合は 0 を返す。
     */
    public Long getScopeIdOrDefault() {
        return scopeId != null ? scopeId : 0L;
    }

    /**
     * postedAsType のデフォルト値を返す。null の場合は USER を返す。
     */
    public String getPostedAsTypeOrDefault() {
        return postedAsType != null ? postedAsType : "USER";
    }
}
