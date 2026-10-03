package com.mannschaft.app.timeline.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timeline.PostDeliveryScope;
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

    /**
     * スコープ ID。チーム/組織タイムラインでは FE が URL の <b>slug 文字列</b>
     * （例: {@code "team-000092"}）を送るため、{@code String} で受け取る。
     * 数値文字列（例: {@code "92"}）・slug いずれも受理する。内部 Long ID への解決は
     * {@code TimelineScopeIdResolver}（書き込み経路）/ {@code TimelineFeedController}
     * （読み取り経路）が {@code TeamService}/{@code OrganizationService} 経由で行う。
     *
     * <p><b>背景</b>: 従来 {@code Long} で受けていたため slug 文字列が Jackson の変換に失敗し、
     * {@code POST /api/v1/timeline/posts} が 400 COMMON_001 で落ちていた（読み取りの feed は
     * slug 解決済みで通るのに、書き込みだけが落ちる非対称）。これを根治するための型変更。</p>
     */
    private final String scopeId;

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
     * 配下配信範囲（省略可）。null の場合は {@link PostDeliveryScope#DIRECT}（現行挙動）。
     *
     * <p>ORGANIZATION スコープの投稿でのみ実効を持つ。チームには階層が無いため
     * TEAM/PUBLIC/PERSONAL/VILLAGE で指定されても配信範囲は変わらない
     * （値自体は保存されるが、フィード・可視性判定のどの述語にも寄与しない）。</p>
     */
    private final PostDeliveryScope deliveryScope;

    /**
     * 既存呼び出し元（システム内部・テスト）との後方互換のため、{@code scopeId} を
     * {@code Long} で受け取る 10 引数コンストラクタを残す。内部では {@code String} に変換して保持する。
     * 新規実装では {@link #CreatePostRequest(String, String, String, String, Long, Long, Long,
     * LocalDateTime, CreatePollRequest, List, PostStatus, UUID)} を利用すること。
     */
    public CreatePostRequest(String content, String scopeType, Long scopeId, String postedAsType,
                             Long postedAsId, Long parentId, Long repostOfId,
                             LocalDateTime scheduledAt, CreatePollRequest poll,
                             List<CreateAttachmentRequest> attachments) {
        this(content, scopeType, scopeId != null ? String.valueOf(scopeId) : null, postedAsType,
                postedAsId, parentId, repostOfId, scheduledAt, poll, attachments, null, null);
    }

    /**
     * F09.13 Phase 2-α-2 後方互換用: scopeVillageId を取らない 11 引数コンストラクタ。
     * {@code scopeId} は {@code Long} で受け取り内部では {@code String} に変換して保持する。
     */
    public CreatePostRequest(String content, String scopeType, Long scopeId, String postedAsType,
                             Long postedAsId, Long parentId, Long repostOfId,
                             LocalDateTime scheduledAt, CreatePollRequest poll,
                             List<CreateAttachmentRequest> attachments, PostStatus status) {
        this(content, scopeType, scopeId != null ? String.valueOf(scopeId) : null, postedAsType,
                postedAsId, parentId, repostOfId, scheduledAt, poll, attachments, status, null);
    }

    /**
     * F17.1 Phase 3: 村スコープを明示指定する完全コンストラクタ。
     *
     * <p>{@code @JsonCreator} を付与することで、複数コンストラクタ存在時に Jackson が
     * デシリアライズ用コンストラクタを一意に特定できるようにしている（これがないと
     * {@code POST /api/v1/timeline/posts} が 500「no suitable creator」で落ちる）。
     * {@code @JsonCreator} は本コンストラクタ <b>ちょうど1つ</b> に限ること。</p>
     *
     * <p>{@code scopeId} は {@code String} で受け取る。FE がチーム/組織タイムラインで送る
     * slug 文字列（例 {@code "team-000092"}）と数値文字列（例 {@code "92"}）の両方を受理し、
     * 内部 Long ID への解決は呼び出し側（リゾルバ）に委ねる。Long で受けると slug が
     * Jackson の変換に失敗して 400 COMMON_001 で落ちるため。</p>
     */
    @JsonCreator
    public CreatePostRequest(
            @JsonProperty("content") String content,
            @JsonProperty("scopeType") String scopeType,
            @JsonProperty("scopeId") String scopeId,
            @JsonProperty("postedAsType") String postedAsType,
            @JsonProperty("postedAsId") Long postedAsId,
            @JsonProperty("parentId") Long parentId,
            @JsonProperty("repostOfId") Long repostOfId,
            @JsonProperty("scheduledAt") LocalDateTime scheduledAt,
            @JsonProperty("poll") CreatePollRequest poll,
            @JsonProperty("attachments") List<CreateAttachmentRequest> attachments,
            @JsonProperty("status") PostStatus status,
            @JsonProperty("scopeVillageId") UUID scopeVillageId,
            @JsonProperty("deliveryScope") PostDeliveryScope deliveryScope) {
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
        this.deliveryScope = deliveryScope;
    }

    /**
     * 12 引数版の後方互換コンストラクタ（{@code deliveryScope} 未指定＝{@code DIRECT} 相当）。
     *
     * <p>{@code @JsonCreator} は 13 引数版<b>ちょうど1つ</b>に付与すること
     * （全 final フィールド + 複数コンストラクタで Jackson が creator を特定できず 500 になるため）。</p>
     */
    public CreatePostRequest(String content, String scopeType, String scopeId, String postedAsType,
                             Long postedAsId, Long parentId, Long repostOfId,
                             LocalDateTime scheduledAt, CreatePollRequest poll,
                             List<CreateAttachmentRequest> attachments, PostStatus status,
                             UUID scopeVillageId) {
        this(content, scopeType, scopeId, postedAsType, postedAsId, parentId, repostOfId,
                scheduledAt, poll, attachments, status, scopeVillageId, null);
    }

    /**
     * 配下配信範囲のデフォルト値を返す。null の場合は {@link PostDeliveryScope#DIRECT}。
     */
    public PostDeliveryScope getDeliveryScopeOrDefault() {
        return deliveryScope != null ? deliveryScope : PostDeliveryScope.DIRECT;
    }

    /**
     * scopeType のデフォルト値を返す。null の場合は PUBLIC を返す。
     */
    public String getScopeTypeOrDefault() {
        return scopeType != null ? scopeType : "PUBLIC";
    }

    /**
     * postedAsType のデフォルト値を返す。null の場合は USER を返す。
     */
    public String getPostedAsTypeOrDefault() {
        return postedAsType != null ? postedAsType : "USER";
    }
}
