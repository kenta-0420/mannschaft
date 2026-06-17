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
 *
 * <p><b>slug 解決</b>: Jackson @RequestBody 受信時は scopeId に slug 文字列（例: "fc-u-18"）
 * または数値文字列を受け付ける。slug が渡された場合は {@link #getScopeId()} が null になり、
 * {@link #getScopeIdRaw()} に元の slug が格納される。Controller 層でスラッグ解決後に
 * {@link #withResolvedScopeId(Long)} で再構築すること。</p>
 */
@Getter
public class CreatePostRequest {

    @NotBlank
    @Size(max = 5000)
    private final String content;

    private final String scopeType;

    private final Long scopeId;

    /**
     * Jackson @RequestBody 受信時の生scopeId文字列（slug または Long文字列）。
     * slug が渡された場合に元の文字列を保持し、Controller 層での slug 解決に使用する。
     * Service/テスト用コンストラクタからは scopeId を Long で渡すため、この値は文字列化された ID になる。
     */
    private final String scopeIdRaw;

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
     * 新規実装では完全コンストラクタを利用すること。
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
     * F17.1 Phase 3: 村スコープを明示指定する完全コンストラクタ（Service/テスト用）。
     * scopeId は Long として渡すこと。slug 解決は呼び出し元（Controller 等）が行う。
     */
    public CreatePostRequest(String content, String scopeType, Long scopeId, String postedAsType,
                             Long postedAsId, Long parentId, Long repostOfId,
                             LocalDateTime scheduledAt, CreatePollRequest poll,
                             List<CreateAttachmentRequest> attachments, PostStatus status,
                             UUID scopeVillageId) {
        this.content = content;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.scopeIdRaw = scopeId != null ? String.valueOf(scopeId) : null;
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
     * 内部用完全コンストラクタ（scopeIdRaw を明示指定する場合）。
     * {@link #fromJson} から呼ばれる。
     */
    private CreatePostRequest(String content, String scopeType, Long scopeId, String scopeIdRaw,
                              String postedAsType, Long postedAsId, Long parentId, Long repostOfId,
                              LocalDateTime scheduledAt, CreatePollRequest poll,
                              List<CreateAttachmentRequest> attachments, PostStatus status,
                              UUID scopeVillageId) {
        this.content = content;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.scopeIdRaw = scopeIdRaw;
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
     * Jackson @RequestBody デシリアライゼーション用ファクトリメソッド。
     *
     * <p>scopeId はチーム/組織ページの URL slug（例: "fc-u-18"）または数値 ID 文字列の
     * どちらでも受け付ける。slug が渡された場合は {@code scopeId=null, scopeIdRaw=slug} として
     * 格納し、Controller 層で slug 解決後に {@link #withResolvedScopeId} で再構築する。</p>
     */
    @JsonCreator
    public static CreatePostRequest fromJson(
                             @JsonProperty("content") String content,
                             @JsonProperty("scopeType") String scopeType,
                             @JsonProperty("scopeId") String scopeIdStr,
                             @JsonProperty("postedAsType") String postedAsType,
                             @JsonProperty("postedAsId") Long postedAsId,
                             @JsonProperty("parentId") Long parentId,
                             @JsonProperty("repostOfId") Long repostOfId,
                             @JsonProperty("scheduledAt") LocalDateTime scheduledAt,
                             @JsonProperty("poll") CreatePollRequest poll,
                             @JsonProperty("attachments") List<CreateAttachmentRequest> attachments,
                             @JsonProperty("status") PostStatus status,
                             @JsonProperty("scopeVillageId") UUID scopeVillageId) {
        // scopeIdStr が数値文字列であれば Long に変換。slug 文字列の場合は null（Controller で解決が必要）
        Long parsedScopeId = null;
        if (scopeIdStr != null) {
            try {
                parsedScopeId = Long.parseLong(scopeIdStr);
            } catch (NumberFormatException e) {
                parsedScopeId = null; // slug 文字列: Controller 層で解決
            }
        }
        return new CreatePostRequest(content, scopeType, parsedScopeId, scopeIdStr,
                postedAsType, postedAsId, parentId, repostOfId,
                scheduledAt, poll, attachments, status, scopeVillageId);
    }

    /**
     * slug → Long 解決後の再構築。Controller が slug 解決済みの scopeId を渡す場合に使用する。
     */
    public CreatePostRequest withResolvedScopeId(Long resolvedScopeId) {
        return new CreatePostRequest(this.content, this.scopeType, resolvedScopeId,
                this.postedAsType, this.postedAsId, this.parentId, this.repostOfId,
                this.scheduledAt, this.poll, this.attachments, this.status, this.scopeVillageId);
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
