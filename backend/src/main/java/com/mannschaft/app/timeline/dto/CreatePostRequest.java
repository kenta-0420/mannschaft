package com.mannschaft.app.timeline.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timeline.PostStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * タイムライン投稿作成リクエストDTO。
 *
 * <p><b>F09.13 Phase 2-α-2</b>: {@link #status} フィールドを追加し、呼び出し元から
 * {@link PostStatus#DRAFT} を明示的に指定可能にした。null の場合は従来通り
 * {@code scheduledAt} の有無で SCHEDULED / PUBLISHED が決まる。</p>
 */
@Getter
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
     * 投稿ステータスの希望値。null の場合は scheduledAt の有無で SCHEDULED / PUBLISHED が決まる。
     * {@link PostStatus#DRAFT} を指定すると下書き保存（タイムライン一覧から除外）となる。
     */
    private final PostStatus status;

    /**
     * 村スコープ ID（F17.1 Phase 3）。{@code scopeType=VILLAGE} の場合に必須。
     */
    private final UUID scopeVillageId;

    /**
     * 既存呼び出し元との後方互換のため、status を取らない 10 引数コンストラクタを残す。
     * 新規実装では {@link #CreatePostRequest(String, String, Long, String, Long, Long, Long,
     * LocalDateTime, CreatePollRequest, List, PostStatus, UUID)} を利用すること。
     */
    public CreatePostRequest(String content, String scopeType, Long scopeId, String postedAsType,
                             Long postedAsId, Long parentId, Long repostOfId,
                             LocalDateTime scheduledAt, CreatePollRequest poll,
                             List<CreateAttachmentRequest> attachments) {
        this(content, scopeType, scopeId, postedAsType, postedAsId, parentId, repostOfId,
                scheduledAt, poll, attachments, null, null);
    }

    /**
     * F09.13 Phase 2-α-2 後方互換用: scopeVillageId を取らない 11 引数コンストラクタ。
     */
    public CreatePostRequest(String content, String scopeType, Long scopeId, String postedAsType,
                             Long postedAsId, Long parentId, Long repostOfId,
                             LocalDateTime scheduledAt, CreatePollRequest poll,
                             List<CreateAttachmentRequest> attachments, PostStatus status) {
        this(content, scopeType, scopeId, postedAsType, postedAsId, parentId, repostOfId,
                scheduledAt, poll, attachments, status, null);
    }

    /**
     * F17.1 Phase 3: 村スコープを明示指定する完全コンストラクタ。
     *
     * <p>{@code @JsonCreator} を付与することで、複数コンストラクタ存在時に Jackson が
     * デシリアライズ用コンストラクタを一意に特定できるようにしている。
     * これがないと {@code POST /api/v1/timeline/posts} が 500（no suitable creator）で落ちる。
     * scopeId は数値（Long）のまま受け取る（String化・slug解決は行わない）。</p>
     */
    @JsonCreator
    public CreatePostRequest(
            @JsonProperty("content") String content,
            @JsonProperty("scopeType") String scopeType,
            @JsonProperty("scopeId") Long scopeId,
            @JsonProperty("postedAsType") String postedAsType,
            @JsonProperty("postedAsId") Long postedAsId,
            @JsonProperty("parentId") Long parentId,
            @JsonProperty("repostOfId") Long repostOfId,
            @JsonProperty("scheduledAt") LocalDateTime scheduledAt,
            @JsonProperty("poll") CreatePollRequest poll,
            @JsonProperty("attachments") List<CreateAttachmentRequest> attachments,
            @JsonProperty("status") PostStatus status,
            @JsonProperty("scopeVillageId") UUID scopeVillageId) {
        this.content = content;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.postedAsType = postedAsType;
        this.postedAsId = postedAsId;
        this.parentId = parentId;
        this.repostOfId = repostOfId;
        this.scheduledAt = scheduledAt;
        this.poll = poll;
        this.attachments = attachments;
        this.status = status;
        this.scopeVillageId = scopeVillageId;
    }

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
