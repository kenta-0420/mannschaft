package com.mannschaft.app.chat.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * メッセージ送信リクエストDTO。
 *
 * <p>F17.1 Phase 3: 村ロビー（{@code channel_type=VILLAGE_LOBBY}）でチーム/組織代表として
 * 発言できるよう {@link #postedAsSubjectType} / {@link #postedAsSubjectId} を追加。
 * 既存呼び出し元との後方互換のため、これらは null 可（null の場合は USER 発言）。</p>
 */
@Getter
public class SendMessageRequest {

    @NotBlank
    private final String body;

    private final Long parentId;

    private final LocalDateTime scheduledAt;

    private final List<AttachmentRequest> attachments;

    /**
     * 投稿主体種別（F17.1 Phase 3）。null の場合は USER（個人発言）扱い。
     */
    private final VillageSubjectType postedAsSubjectType;

    /**
     * 投稿主体 ID（F17.1 Phase 3）。TEAM/ORGANIZATION の場合に必須。
     */
    private final Long postedAsSubjectId;

    /**
     * 既存呼び出し元との後方互換のためのコンストラクタ。
     */
    public SendMessageRequest(String body, Long parentId, LocalDateTime scheduledAt,
                              List<AttachmentRequest> attachments) {
        this(body, parentId, scheduledAt, attachments, null, null);
    }

    /**
     * F17.1 Phase 3: 投稿主体を明示指定する完全コンストラクタ。
     *
     * <p>{@code @JsonCreator} を付与することで、複数コンストラクタ存在時に Jackson が
     * デシリアライズ用コンストラクタを一意に特定できるようにしている。
     * これがないと {@code POST /api/v1/chat/channels/{id}/messages} が
     * 500（no suitable creator）で落ちる。</p>
     */
    @JsonCreator
    public SendMessageRequest(
            @JsonProperty("body") String body,
            @JsonProperty("parentId") Long parentId,
            @JsonProperty("scheduledAt") LocalDateTime scheduledAt,
            @JsonProperty("attachments") List<AttachmentRequest> attachments,
            @JsonProperty("postedAsSubjectType") VillageSubjectType postedAsSubjectType,
            @JsonProperty("postedAsSubjectId") Long postedAsSubjectId) {
        this.body = body;
        this.parentId = parentId;
        this.scheduledAt = scheduledAt;
        this.attachments = attachments;
        this.postedAsSubjectType = postedAsSubjectType;
        this.postedAsSubjectId = postedAsSubjectId;
    }
}
