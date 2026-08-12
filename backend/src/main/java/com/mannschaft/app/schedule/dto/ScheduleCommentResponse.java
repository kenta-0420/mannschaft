package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F03.16 予定コメントのレスポンス表現（設計書 §4.2 / §4.3）。
 *
 * <p>{@code parentId} / {@code rootId} / {@code body} / {@code author} / {@code replies} は
 * {@code null} のとき応答から省略する（{@link JsonInclude.Include#NON_NULL}）。
 * トゥームストーン（{@code isDeleted=true}）は {@code body} / {@code author} を必ず {@code null} にする
 * （削除された本文を BE から一切送らない・設計書 §5.3）。</p>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ScheduleCommentResponse")
public class ScheduleCommentResponse {

    private final UUID id;
    private final Long scheduleId;
    private final UUID parentId;
    private final UUID rootId;
    private final int depth;
    private final String body;

    @JsonProperty("isEdited")
    private final boolean edited;

    @JsonProperty("isDeleted")
    private final boolean deleted;

    private final int replyCount;
    private final CommentAuthorResponse author;
    private final boolean canEdit;
    private final boolean canDelete;
    private final Instant createdAt;
    private final Instant updatedAt;

    /** 返信配列（最大3件同梱）。返信行（depth=1）では常に {@code null}（無限ネスト禁止）。 */
    private final List<ScheduleCommentResponse> replies;
}
