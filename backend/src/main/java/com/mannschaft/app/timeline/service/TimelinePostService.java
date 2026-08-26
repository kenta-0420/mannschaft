package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.timeline.AttachmentType;
import com.mannschaft.app.timeline.VideoProcessingStatus;
import com.mannschaft.app.timeline.event.TimelinePostCreatedEvent;
import com.mannschaft.app.timeline.PostDeliveryScope;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.AttachmentResponse;
import com.mannschaft.app.timeline.dto.CreateAttachmentRequest;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PollResponse;
import com.mannschaft.app.timeline.dto.PostDetailResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.UpdatePostRequest;
import com.mannschaft.app.timeline.entity.TimelinePostAttachmentEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEditEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostAttachmentRepository;
import com.mannschaft.app.timeline.repository.TimelinePostEditRepository;
import com.mannschaft.app.timeline.repository.TimelinePostReactionRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * タイムライン投稿サービス。投稿のCRUD・フィード取得・検索を担当する。
 *
 * <p><b>F13 Phase 4-γ</b>: 投稿作成（添付ファイル含む）時と投稿削除時に
 * {@link StorageQuotaService} を通じてストレージ使用量を計上する。
 * presign 時の checkQuota は {@link TimelineVideoAttachmentService#generateUploadUrl} で実施済み。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelinePostService {

    private static final int MAX_ATTACHMENTS = 10;
    private static final int DEFAULT_FEED_SIZE = 20;
    /** 投稿詳細に同梱するリプライプレビュー（会話の古い順・先頭から）の最大件数。 */
    private static final int RECENT_REPLIES_LIMIT = 5;

    /** F13 Phase 4-γ: storage_usage_logs.reference_type に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "timeline_post_attachments";

    /**
     * 認可根治 Wave3-B7-timeline: 村所属が空のユーザーで JPQL の {@code IN ()} 構文エラーを避けるための
     * ダミー UUID（nil UUID）。UUIDv7 は常に非ゼロのタイムスタンプ prefix を持つため実在の村 ID と
     * 衝突しない（{@code findMyFeed} の {@code -1L} ダミーと同じ考え方）。
     */
    private static final UUID NIL_VILLAGE_ID_SENTINEL = new UUID(0L, 0L);

    private final TimelinePostRepository postRepository;
    private final TimelinePostAttachmentRepository attachmentRepository;
    private final TimelinePostEditRepository editRepository;
    private final TimelinePostReactionRepository reactionRepository;
    private final TimelinePollService pollService;
    private final TimelineMapper timelineMapper;
    private final DomainEventPublisher domainEventPublisher;
    private final R2StorageService r2StorageService;
    /** F13 Phase 4-γ: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;
    /** F17.1 Phase 3: scope=VILLAGE 投稿の主体検証。 */
    private final PostingIdentityService postingIdentityService;
    /** TEAM/ORGANIZATION スコープへの投稿時のメンバーシップ検証。 */
    private final AccessControlService accessControlService;
    /**
     * 個人ダッシュボード集約タイムライン（マイフィード）の所属スコープ解決用。
     * ドメイン境界原則に従い membership ドメインの Repository を直注入せず Service 経由で利用する
     * （プリミティブ {@code List<Long>} のみを受け取り Entity を漏らさない）。
     */
    private final com.mannschaft.app.membership.service.MembershipService membershipService;
    /**
     * 個人集約タイムラインの投稿元（team/org 名）・著者（表示名/アバター）・代理主体（team/org 名/ロゴ）の
     * バッチ名前解決。N+1 を避けるため種別ごとに 1 回だけ呼ぶ（{@link #enrichPosts}）。
     * ドメイン境界原則に従い team/organization/user の Entity を直参照せず、プリミティブ Map のみを受け取る。
     */
    private final NameResolverService nameResolverService;
    /**
     * 投稿元スコープの slug 一括解決用（TEAM）。ドメイン境界原則に従い Repository を直注入せず
     * Service 経由で slug（プリミティブ）のみを取得する（{@code TimelineScopeIdResolver} と同方針）。
     */
    private final com.mannschaft.app.team.service.TeamService teamService;
    /** 投稿元スコープの slug 一括解決用（ORGANIZATION）。 */
    private final com.mannschaft.app.organization.service.OrganizationService organizationService;
    /**
     * 画像添付の R2 生キー（{@code file_key}）を署名付き表示 URL へ解決する共通部品（#2377 で全域導入）。
     * FE は R2 を署名できないため、DB の生キーをそのまま返すと 404 になる。issue #2424 の根治で
     * timeline も他ドメイン（village/blog 等）に倣って本部品を通す。存在検証は行わず署名 URL 生成のみ。
     */
    private final MediaUrlResolver mediaUrlResolver;
    /**
     * 投稿可視性判定の正準実装（認可根治 Wave7）。読取経路（{@link #getPostDetail} /
     * {@link #getReplies}）・書き込み経路（リプライ/リポストの参照先検証）が共有する唯一の述語。
     */
    private final TimelinePostVisibilityAccessGuard postVisibilityGuard;
    /** 投稿の更新・削除・ピン留め切替の管理操作ゲート（本人 or TEAM/ORGANIZATION スコープの ADMIN+）。 */
    private final TimelinePostAccessGuard postAccessGuard;
    /**
     * 配下配信（delivery_scope）の到達範囲を求める唯一の正準実装。マイフィード・ユーザー投稿一覧・
     * 検索・可視性ゲートの 4 経路が本部品を共有する（重複実装すると必ずズレるため）。
     */
    private final TimelineDeliveryScopeResolver deliveryScopeResolver;
    /** マイフィードのミュート除外用（表示設定であり認可ではない。本サービスのフィード経路限定）。 */
    private final com.mannschaft.app.timeline.repository.UserMuteRepository muteRepository;

    /** {@code user_mutes.muted_type} のチーム種別。 */
    private static final String MUTED_TYPE_TEAM = "TEAM";
    /** {@code user_mutes.muted_type} の組織種別。 */
    private static final String MUTED_TYPE_ORGANIZATION = "ORGANIZATION";

    /**
     * {@code NOT IN} 句へ安全に渡せるリストへ変換する。
     *
     * <p><b>{@code IN} 用のダミーと意味が反転する</b>点に注意。scope ID は常に正の値なので
     * {@code NOT IN (-1)} は「全件通過（除外なし）」を意味する。{@code IN (-1)} の
     * 「1 件もマッチしない」と取り違えると、フィードが全件消えるか全件通るかに倒れる。</p>
     */
    private static List<Long> safeNotInList(List<Long> ids) {
        return (ids == null || ids.isEmpty()) ? List.of(-1L) : ids;
    }

    /** 名前解決フォールバック（退会・削除・匿名化で Map に存在しない場合）。 */
    private static final String UNKNOWN_USER_NAME = "不明なユーザー";
    private static final String UNKNOWN_TEAM_NAME = "不明なチーム";
    private static final String UNKNOWN_ORG_NAME = "不明な組織";

    /**
     * 投稿を作成する（解決済みスコープ ID 版）。添付ファイル・投票も同時に作成する。
     *
     * <p>コントローラーは {@code TimelineScopeIdResolver} で slug/Long 文字列を内部 Long ID に
     * 解決してから本メソッドを呼ぶ。これにより GET feed（解決済み）と対称になり、FE が slug を
     * 送る書き込み経路の 400 を根治する。TEAM/ORGANIZATION スコープへの投稿時は解決済み ID で
     * メンバーシップチェックを行い、非メンバーによる投稿を禁止する。システム内部からの自動投稿には
     * {@link #createSystemPost(CreatePostRequest, Long)} を使うこと。</p>
     *
     * @param req             作成リクエスト
     * @param resolvedScopeId 解決済みの内部スコープ Long ID
     * @param userId          ユーザーID
     * @return 作成された投稿
     */
    @Transactional
    public PostResponse createPost(CreatePostRequest req, Long resolvedScopeId, Long userId) {
        // VILLAGE への投稿権限は doCreatePost の validatePostingIdentity が
        // 投稿主体（USER / TEAM / ORGANIZATION）単位で検証する。ここで呼び出し元 userId 単位の
        // 村メンバー判定を重ねると、「投稿者本人は村メンバーではないが所属チームが村メンバー」
        // という正当なチーム代理投稿を誤って弾くため、VILLAGE の認可は下流の主体検証へ委譲する
        // （素通しではなく、より粒度の細かい検証に委ねる）。
        //
        // なお本判定を private ヘルパーに切り出すと、認可番人（AuthzControllerGuardArchTest）の
        // 委譲追跡（MAX_DELEGATION_DEPTH = 2）で accessControlService の呼び出しが
        // 3 ホップ目に沈み検出されなくなる。番人に見える位置を保つため、ここは
        // checkScopeMembership を直接呼ぶ形にフラット化している。
        if (parseScopeType(req.getScopeTypeOrDefault()) != PostScopeType.VILLAGE) {
            checkScopeMembership(req.getScopeTypeOrDefault(), resolvedScopeId, userId);
        }
        // 配下配信（CHILDREN / DESCENDANTS）の送信権限ゲート。
        //
        // 配下配信は「投稿が届く人の集合」を組織階層ぶん広げる操作であり、組織名義投稿
        // （posted_as_type=ORGANIZATION）が既に ADMIN/DEPUTY_ADMIN を要求しているのに対して
        // 影響範囲がより広い。在籍しているだけの MEMBER / SUPPORTER が最上位組織から
        // 配下全体へ周知を流せる状態は権限の逆転であるため、組織名義投稿と同じ作法
        // （AccessControlService#checkAdminOrAbove ＝ 違反時 403 COMMON_002）に揃えて塞ぐ。
        //
        // 課すのは「新規投稿でクライアントが明示指定した」場合のみ（parentId == null）。
        // 返信は親投稿から delivery_scope を継承する（doCreatePost）ため、ここで返信にも課すと
        // 配下配信で届いた投稿へ一般メンバーが返信できなくなる。継承経路には課さないこと。
        //
        // ORGANIZATION 以外（TEAM/PUBLIC/PERSONAL/VILLAGE）では delivery_scope は配信・可視性の
        // どの述語にも寄与しない（到達範囲を計算する TimelineDeliveryScopeResolver は組織階層のみを
        // 展開する）。値が保存されても誰にも余分に届かないため権限を要求する理由が無く、
        // 指定値をそのまま保存する既存契約も変えない。
        //
        // なお checkScopeMembership と同じ理由（認可番人 AuthzControllerGuardArchTest の
        // 委譲追跡 MAX_DELEGATION_DEPTH = 2）で、accessControlService の呼び出しは
        // private ヘルパーへ沈めず本メソッド直下に置く。
        if (req.getParentId() == null
                && req.getDeliveryScopeOrDefault() != PostDeliveryScope.DIRECT
                && parseScopeType(req.getScopeTypeOrDefault()) == PostScopeType.ORGANIZATION) {
            accessControlService.checkAdminOrAbove(userId, resolvedScopeId, "ORGANIZATION");
        }
        // 上記は「リクエストが申告したスコープ」に対する検証である。リプライは親投稿から
        // スコープを継承するため申告値と実効値が食い違いうる。継承後の実効スコープに対する
        // 再評価は doCreatePost（enforceScopeAuthorization = true）が担う。
        return doCreatePost(req, resolvedScopeId, userId, true);
    }

    /**
     * 投稿を作成する（後方互換オーバーロード）。
     *
     * <p>{@code req.getScopeId()} が数値文字列であることを前提に内部 Long ID へ parse して
     * {@link #createPost(CreatePostRequest, Long, Long)} に委譲する。slug 解決を伴わない
     * システム内部・既存呼び出し元（例: {@code TimelinePostAnnouncementAdapter}）・テスト向け。
     * HTTP 経由でユーザーが slug を送るケースはコントローラーで解決済みのため本オーバーロードは通らない。</p>
     *
     * @param req    作成リクエスト（scopeId は数値文字列または null）
     * @param userId ユーザーID
     * @return 作成された投稿
     */
    @Transactional
    public PostResponse createPost(CreatePostRequest req, Long userId) {
        return createPost(req, parseInternalScopeId(req), userId);
    }

    /**
     * システム内部からのタイムライン投稿（メンバーシップチェックをスキップ）。
     *
     * <p>ユーザー操作ではなくバッチ/イベント/サービス連携で自動投稿する場合に使う。
     * 例: {@code PropertyWorkPackageService.publishToTimeline()} による物件履歴の自動投稿。</p>
     *
     * <p><strong>注意</strong>: このメソッドは呼び出し元がシステム内部の信頼済みコードであることを
     * 前提とする。ユーザー入力を直接受け付けるコントローラーからは必ず {@link #createPost} を使うこと。</p>
     *
     * @param req    作成リクエスト
     * @param userId 投稿者ユーザーID（システムアクターのID）
     * @return 作成された投稿
     */
    @Transactional
    public PostResponse createSystemPost(CreatePostRequest req, Long userId) {
        return doCreatePost(req, parseInternalScopeId(req), userId, false);
    }

    /**
     * 村行事の還流専用のシステム名義投稿を作成する（F17.2 Wave2 ①・設計書 §3.2/§3.3）。
     *
     * <p>行事が「立った／近づいた／確定した／始まった」ときに、村のタイムラインへ
     * <b>システム名義（投稿者ユーザー不在）</b>の投稿を1行作る。設計書 §3.2 の契約に従い、
     * {@code user_id = NULL}・{@code posted_as_type} は既定 {@code USER} のまま（無視される）・
     * {@code status = PUBLISHED}・{@code scope_type = VILLAGE}・{@code scope_id = 0}・
     * {@code scope_village_id = villageId} で保存する。システム投稿の判定は
     * {@code system_post_type IS NOT NULL} の一本槍であり、{@code posted_as_type} は読まない。</p>
     *
     * <p><b>本メソッドは「村行事の還流専用」であり、汎用の「任意本文を任意村へ投げる API」ではない</b>。
     * 種別を {@link VillageEventNotificationType} enum 引数で縛ることで用途を型で限定する。</p>
     *
     * <p><b>認可はこのメソッドを呼ぶ村ドメインの Service 側の責務</b>である（ドメイン越境は
     * Service メソッド呼び出しのみ・原則1/5・設計書 §3.3）。本メソッドはメンバーシップ検証を
     * 行わない（呼び出し元がシステム内部の信頼済みコードであることを前提とする）。
     * 通知・自動投稿の発火配線（バッチ／afterCommit）は後続の村ドメイン側で結線する（設計書 §3.3.1）。</p>
     *
     * @param villageId       投稿先の村 UUID（{@code scope_village_id}）
     * @param systemPostType  システム投稿の種別（村ドメイン enum・{@code system_post_type} へ {@code .name()} で格納）
     * @param sourceEventUuid 対象行事の UUID（歳時記/祭/寄合の {@code id}）。冪等判定・タップ遷移に使う
     * @param content         投稿本文（i18n 由来の要約等。呼び出し元で組み立てる）
     * @return 作成された投稿（システム投稿なので {@code postedAs}/{@code user} は付与されない）
     */
    @Transactional
    public PostResponse createSystemVillagePost(UUID villageId,
                                                VillageEventNotificationType systemPostType,
                                                UUID sourceEventUuid,
                                                String content) {
        if (villageId == null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        if (systemPostType == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        TimelinePostEntity post = TimelinePostEntity.builder()
                .scopeType(PostScopeType.VILLAGE)
                .scopeId(0L)
                .scopeVillageId(villageId)
                .userId(null)                              // システム投稿は投稿者ユーザー不在（§3.2）
                .postedAsType(PostedAsType.USER)           // 既定値のまま・判定には使わない（§3.2）
                .systemPostType(systemPostType.name())     // system_post_type IS NOT NULL がシステム投稿の唯一の判定
                .sourceEventUuid(sourceEventUuid)
                .content(content)
                .status(PostStatus.PUBLISHED)
                .build();

        post = postRepository.save(post);
        log.info("村行事システム投稿作成: id={}, villageId={}, type={}, sourceEventUuid={}",
                post.getId(), villageId, systemPostType, sourceEventUuid);

        return timelineMapper.toPostResponse(post);
    }

    /**
     * 村行事のシステム自動投稿が既に存在するかを判定する（F17.2 Wave2 ①・冪等判定・設計書 §3.7）。
     *
     * <p>{@code (scope_village_id, system_post_type, source_event_uuid)} の存在チェック。
     * 村ドメインの還流サービスが EVENT_UPCOMING 等の二重投稿を防ぐために呼ぶ
     * （ドメイン越境は Service メソッド経由・原則1/5）。</p>
     */
    public boolean systemVillagePostExists(UUID villageId, VillageEventNotificationType systemPostType,
                                           UUID sourceEventUuid) {
        if (villageId == null || systemPostType == null || sourceEventUuid == null) {
            return false;
        }
        return postRepository.existsByScopeVillageIdAndSystemPostTypeAndSourceEventUuid(
                villageId, systemPostType.name(), sourceEventUuid);
    }

    /**
     * 指定 ID 群のうち生存している（timeline {@code deleted_at} でない）当該村の VILLAGE 投稿 ID を返す
     * （F17.2 Wave2 ③・実況一覧/村史編纂の削除済み除外・AC-17c）。
     */
    public Set<Long> filterAliveVillagePostIds(java.util.Collection<Long> postIds, UUID villageId) {
        if (postIds == null || postIds.isEmpty() || villageId == null) {
            return java.util.Set.of();
        }
        return new HashSet<>(postRepository.findAliveVillagePostIds(postIds, villageId));
    }

    /**
     * 指定投稿が「生存している当該村の VILLAGE 投稿」であるかを判定する（F17.2 Wave2 ③・実況タグ付けの検証）。
     */
    public boolean isAliveVillagePost(Long postId, UUID villageId) {
        if (postId == null || villageId == null) {
            return false;
        }
        return !postRepository.findAliveVillagePostIds(List.of(postId), villageId).isEmpty();
    }

    /**
     * スコープに応じたメンバーシップチェックを行う（ユーザー操作用）。
     *
     * <p><b>認可根治 Wave6</b>: {@link PostScopeType} の <b>全 8 値を網羅的にディスパッチ</b>し、
     * 未対応・未知の種別は {@code default} で fail-closed に倒す。呼び出し元が渡す
     * {@code scopeTypeStr} / {@code resolvedScopeId} はいずれもリクエスト由来（クエリパラメータ・
     * リクエストボディ）であり攻撃者が自由に指定できるため、「知っている種別だけ検証し
     * 残りは黙って通す」構造を残してはならない。</p>
     *
     * <ul>
     *   <li>PUBLIC: 検証なし（公開スコープ。{@code scope_id} は意味を持たない）</li>
     *   <li>TEAM / ORGANIZATION: {@link AccessControlService#checkMembership} でメンバー確認（非メンバーは 403）</li>
     *   <li>PERSONAL: {@code scope_id} は投稿者本人の {@code users.id}
     *       （唯一の生成経路である行動メモ終業投稿がそう積む）。呼び出し元本人と一致しなければ 403</li>
     *   <li>VILLAGE: 村の識別子は {@code scope_id} ではなく {@code scope_village_id}（UUID）側にあり、
     *       {@code scope_id} は常に 0 で全村衝突する。したがって本メソッド経由の VILLAGE 指定は
     *       fail-closed とし、{@link #getFeed} / {@link #getPinnedPosts} の
     *       {@code scopeVillageId} 経由（{@link #requireVillageMember}）を正路とする</li>
     *   <li>FRIEND_TEAM / FRIEND_FORWARD / FRIEND_ARCHIVE: 汎用タイムライン経路は正路ではない。
     *       正路は social ドメインの friend-feed API（{@code MANAGE_FRIEND_TEAMS} 権限が必要）であり、
     *       汎用経路を開けると正路より緩い読み口を作ることになるため fail-closed とする</li>
     * </ul>
     *
     * @param scopeTypeStr    スコープ種別文字列
     * @param resolvedScopeId 解決済みの内部スコープ Long ID
     * @param userId          操作ユーザーID
     */
    private void checkScopeMembership(String scopeTypeStr, Long resolvedScopeId, Long userId) {
        switch (parseScopeType(scopeTypeStr)) {
            case PUBLIC -> {
                // 公開スコープ。誰でも読み書きできるため追加の検証はしない。
            }
            case TEAM -> accessControlService.checkMembership(userId, resolvedScopeId, "TEAM");
            case ORGANIZATION ->
                    accessControlService.checkMembership(userId, resolvedScopeId, "ORGANIZATION");
            case PERSONAL -> requireSelfScope(resolvedScopeId, userId);
            case VILLAGE, FRIEND_TEAM, FRIEND_FORWARD, FRIEND_ARCHIVE ->
                    throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * スコープ種別文字列を {@link PostScopeType} に変換する。
     *
     * <p>未知の文字列は {@link IllegalArgumentException}（＝ 500）ではなく
     * {@link TimelineErrorCode#POST_NOT_FOUND} に倒す。スコープ種別は利用者が
     * 自由に指定できるため、不正値で 500 を返すと入力起因の障害が
     * サーバー障害として計上されてしまう。</p>
     */
    private PostScopeType parseScopeType(String scopeTypeStr) {
        try {
            return PostScopeType.valueOf(scopeTypeStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }
    }

    /**
     * PERSONAL スコープが呼び出し元本人のものであることを検証する（認可根治 Wave6）。
     *
     * <p>PERSONAL の {@code scope_id} は投稿者本人の {@code users.id} であるため、
     * 「{@code scope_id} == 呼び出し元 userId」が自己スコープの成立条件になる。
     * これによりフィード/ピン留めのリポジトリ引きも実質的に呼び出し元 ID との
     * 複合キーとなり、他人の PERSONAL 投稿には到達できない。</p>
     */
    private void requireSelfScope(Long resolvedScopeId, Long userId) {
        if (userId == null || !userId.equals(resolvedScopeId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * システム内部・後方互換経路向けに {@code req.getScopeId()}（数値文字列）を内部 Long ID へ parse する。
     *
     * <p>slug 解決は伴わない（HTTP 経由の slug はコントローラーで解決済み）。null/空文字は {@code 0L}、
     * 数値文字列はそのまま parse する。万一 slug 等の非数値が来た場合は誤った scope への投稿を防ぐため
     * {@link TimelineErrorCode#POST_NOT_FOUND} を投げる（握り潰さず根治）。</p>
     */
    private Long parseInternalScopeId(CreatePostRequest req) {
        String raw = req.getScopeId();
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }
    }

    /**
     * 投稿作成の共通ロジック（メンバーシップチェックなし）。
     *
     * <p>{@link #createPost} と {@link #createSystemPost} の両方から呼ばれる。
     * バリデーション・ステータス決定・Entity 生成・添付ファイル保存・イベント発行を担う。</p>
     *
     * <p><b>リプライのスコープ継承</b>: {@code parentId} が指定されている場合、リクエストの
     * scopeType/scopeId/scopeVillageId を無視して親投稿のスコープを継承する。
     * これにより「TEAMスコープ投稿へのリプライがPUBLICで作成される」情報漏洩を防ぐ。</p>
     *
     * <p><b>認可根治 Wave6</b>: 継承によってリクエストの申告値と実際に保存されるスコープが
     * 食い違うため、{@code enforceScopeAuthorization} が true の場合は
     * <b>継承後の実効スコープ</b>に対して {@link #requireReplyableParent} で認可を再評価する。
     * 非リプライ時は申告値＝実効値（{@code resolvedScopeId} をそのまま採用する）であり、
     * {@link #createPost} が入口で行う {@link #checkScopeMembership} が実効スコープの検証に一致する。</p>
     *
     * @param req                        作成リクエスト
     * @param resolvedScopeId            解決済みの内部スコープ Long ID（非リプライ時の {@code effectiveScopeId}）
     * @param userId                     投稿者ユーザーID
     * @param enforceScopeAuthorization  実効スコープの認可を評価するか（ユーザー操作は true・
     *                                   {@link #createSystemPost} 経由のシステム自動投稿は false）
     */
    private PostResponse doCreatePost(CreatePostRequest req, Long resolvedScopeId, Long userId,
                                      boolean enforceScopeAuthorization) {
        if (req.getContent() == null || req.getContent().isBlank()) {
            if (req.getRepostOfId() == null && req.getPoll() == null) {
                throw new BusinessException(TimelineErrorCode.EMPTY_POST_CONTENT);
            }
        }

        if (req.getAttachments() != null && req.getAttachments().size() > MAX_ATTACHMENTS) {
            throw new BusinessException(TimelineErrorCode.MAX_ATTACHMENTS_EXCEEDED);
        }

        // F09.13 Phase 2-α-2: 呼び出し元が明示的に DRAFT を指定した場合は尊重する。
        // それ以外は従来通り scheduledAt の有無で SCHEDULED / PUBLISHED を決定する。
        // DRAFT 投稿は TimelinePostRepository の各クエリが status='PUBLISHED' で絞っているため
        // 通常一覧・検索・ピン留め一覧から自動除外される。
        PostStatus status;
        if (req.getStatus() == PostStatus.DRAFT) {
            status = PostStatus.DRAFT;
        } else if (req.getScheduledAt() != null) {
            status = PostStatus.SCHEDULED;
        } else {
            status = PostStatus.PUBLISHED;
        }

        // リプライの場合、親投稿のスコープを継承する（情報漏洩防止）。
        // リクエストで明示されたスコープ値ではなく、必ず親投稿のスコープを正とする。
        // 親が存在しない場合は POST_NOT_FOUND をスローする。
        TimelinePostEntity parentPost = null;
        if (req.getParentId() != null) {
            parentPost = postRepository.findById(req.getParentId())
                    .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND));
            if (enforceScopeAuthorization) {
                // 認可根治 Wave6: 継承元となる親投稿そのものへの到達可否を先に判定する
                // （読めないスコープの投稿にリプライを積めないようにする）。
                requireReplyableParent(parentPost, userId);
            }
        }

        // 認可根治 Wave6: リポスト元は「呼び出し元から見える投稿」に限る。
        // リプライの継承と同じく、リクエストが渡した投稿 ID をそのまま参照して
        // 書き込み（リポスト数の加算）を行う経路のため、参照先の可視性を先に検証する。
        TimelinePostEntity repostOriginal = null;
        if (req.getRepostOfId() != null) {
            repostOriginal = postRepository.findById(req.getRepostOfId()).orElse(null);
            if (enforceScopeAuthorization && repostOriginal != null
                    && !postVisibilityGuard.isVisible(repostOriginal, userId)) {
                throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
            }
        }

        // F17.1 Phase 3: scope=VILLAGE 投稿の主体検証
        // リプライ時は親スコープを使用するため req.getScopeTypeOrDefault() は使わない
        PostScopeType scopeTypeEnum;
        Long effectiveScopeId;
        UUID scopeVillageId;
        PostDeliveryScope effectiveDeliveryScope;
        if (parentPost != null) {
            // リプライ: 親投稿のスコープをそのまま継承する
            scopeTypeEnum = parentPost.getScopeType();
            effectiveScopeId = parentPost.getScopeId();
            scopeVillageId = parentPost.getScopeVillageId();
            // 配下配信範囲も必ず親から継承する。ここでクライアント指定値（既定 DIRECT）を
            // 入れると、配下配信で届いた投稿への返信が scope_id=上位組織 かつ
            // delivery_scope=DIRECT の行になり、返信者自身からも 404 になる
            // （フィードには出るのに直リンクでは 404、という本 PR が撲滅した非対称を
            // 返信という形で再生産してしまう）。スコープ3点と同じく親が正である。
            effectiveDeliveryScope = parentPost.getDeliveryScope() != null
                    ? parentPost.getDeliveryScope()
                    : PostDeliveryScope.DIRECT;
        } else {
            scopeTypeEnum = parseScopeType(req.getScopeTypeOrDefault());
            effectiveScopeId = resolvedScopeId != null ? resolvedScopeId : 0L;
            scopeVillageId = scopeTypeEnum == PostScopeType.VILLAGE ? req.getScopeVillageId() : null;
            effectiveDeliveryScope = req.getDeliveryScopeOrDefault();
        }

        PostedAsType postedAsTypeEnum = PostedAsType.valueOf(req.getPostedAsTypeOrDefault());
        Long postedAsId = req.getPostedAsId();

        if (scopeTypeEnum == PostScopeType.VILLAGE) {
            // VILLAGE スコープの検証。リプライで親から村 ID を継承した場合も同じ検証を通す
            // （投稿主体単位の検証はリプライにも等しく必要なため、経路で分岐させない）。
            if (scopeVillageId == null) {
                throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
            }
            // PostedAsType と VillageSubjectType は同名（USER/TEAM/ORGANIZATION）でマッピング可能
            VillageSubjectType subjectType = VillageSubjectType.valueOf(postedAsTypeEnum.name());
            Long subjectId = subjectType == VillageSubjectType.USER
                    ? userId
                    : postedAsId;
            postingIdentityService.validatePostingIdentity(
                    userId, scopeVillageId, subjectType, subjectId);
            // USER の場合は postedAsId に投稿者本人 ID を入れる（既存挙動も同様）
            if (subjectType == VillageSubjectType.USER) {
                postedAsId = userId;
            }
        }

        TimelinePostEntity post = TimelinePostEntity.builder()
                .scopeType(scopeTypeEnum)
                .scopeId(effectiveScopeId)
                .scopeVillageId(scopeVillageId)
                // 配下配信範囲。リプライは親から継承、新規投稿は省略時 DIRECT（現行挙動）。
                // ORGANIZATION 以外では保存されても配信範囲に寄与しない（チームに階層が無いため）。
                .deliveryScope(effectiveDeliveryScope)
                .userId(userId)
                .postedAsType(postedAsTypeEnum)
                .postedAsId(postedAsId)
                .parentId(req.getParentId())
                .content(req.getContent())
                .repostOfId(req.getRepostOfId())
                .status(status)
                .scheduledAt(req.getScheduledAt())
                .build();

        post = postRepository.save(post);

        // リプライの場合、親投稿のリプライ数をインクリメント
        // （親投稿は上記で既に取得済みなのでそれを直接使う）
        if (parentPost != null) {
            parentPost.incrementReplyCount();
            postRepository.save(parentPost);
        }

        // リポストの場合、元投稿のリポスト数をインクリメント
        // （元投稿は上記の可視性検証で既に取得済みなのでそれを直接使う）
        if (repostOriginal != null) {
            repostOriginal.incrementRepostCount();
            postRepository.save(repostOriginal);
        }

        // 添付ファイルの保存
        // リプライ時は継承済みの scopeTypeEnum/effectiveScopeId を使う（req の値は使わない）
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            ScopeResolution scope = resolveScope(scopeTypeEnum.name(), effectiveScopeId, userId);
            saveAttachments(post.getId(), req.getAttachments(), scope, userId);
        }

        // 投票の保存
        if (req.getPoll() != null) {
            pollService.createPoll(post.getId(), req.getPoll());
        }

        log.info("タイムライン投稿作成: id={}, userId={}, scopeType={}", post.getId(), userId, scopeTypeEnum);

        // 即時公開投稿のみゲーミフィケーションイベントを発行（予約投稿はスキップ）
        if (status == PostStatus.PUBLISHED) {
            domainEventPublisher.publish(new TimelinePostCreatedEvent(
                    post.getId(), userId,
                    scopeTypeEnum.name(),
                    effectiveScopeId
            ));
        }

        return timelineMapper.toPostResponse(post);
    }

    /**
     * 投稿を更新する。編集履歴を記録する。
     *
     * <p><b>認可根治 Wave7</b>: 投稿者本人、または TEAM/ORGANIZATION スコープの ADMIN/DEPUTY_ADMIN が
     * 更新できる（{@link TimelinePostAccessGuard#checkCanManage}）。それ以外は
     * {@link TimelineErrorCode#NOT_POST_OWNER}。</p>
     *
     * @param postId 投稿ID
     * @param req    更新リクエスト
     * @param userId ユーザーID
     * @return 更新された投稿
     */
    @Transactional
    public PostResponse updatePost(Long postId, UpdatePostRequest req, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);
        postAccessGuard.checkCanManage(userId, post);

        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new BusinessException(TimelineErrorCode.EMPTY_POST_CONTENT);
        }

        // 編集履歴の記録
        TimelinePostEditEntity edit = TimelinePostEditEntity.builder()
                .timelinePostId(postId)
                .contentBefore(post.getContent())
                .build();
        editRepository.save(edit);

        post.updateContent(req.getContent());
        post = postRepository.save(post);

        log.info("タイムライン投稿更新: id={}, editCount={}", postId, post.getEditCount());
        return timelineMapper.toPostResponse(post);
    }

    /**
     * 投稿を論理削除する。
     *
     * <p><b>F13 Phase 4-γ</b>: 論理削除完了後に添付ファイル（IMAGE / VIDEO_FILE）の
     * 使用量を {@link StorageQuotaService#recordDeletion} で減算する。</p>
     *
     * <p><b>認可根治 Wave7</b>: 投稿者本人、または TEAM/ORGANIZATION スコープの ADMIN/DEPUTY_ADMIN が
     * 削除できる（{@link TimelinePostAccessGuard#checkCanManage}）。</p>
     *
     * @param postId 投稿ID
     * @param userId ユーザーID
     */
    @Transactional
    public void deletePost(Long postId, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);
        postAccessGuard.checkCanManage(userId, post);

        // F13 Phase 4-γ: 削除前に添付ファイル情報を取得してクォータ減算に備える
        List<TimelinePostAttachmentEntity> attachments =
                attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(postId);

        post.softDelete();
        postRepository.save(post);

        // リプライ削除時は親投稿のリプライ数をデクリメントする（作成時 doCreatePost の +1 と対称）。
        // findPostOrThrow は @SQLRestriction("deleted_at IS NULL") により削除済み投稿を取得しないため、
        // 本メソッドに到達した時点で「今回初めて削除される」ことが保証される（二重デクリメント防止＝冪等）。
        // 親が既に削除済み（findById が空）の場合は減算をスキップする（安全）。負値ガードは
        // TimelinePostEntity#decrementReplyCount 内で担保する（0 でクランプ）。
        if (post.getParentId() != null) {
            postRepository.findById(post.getParentId()).ifPresent(parent -> {
                parent.decrementReplyCount();
                postRepository.save(parent);
            });
        }

        log.info("タイムライン投稿削除: id={}, userId={}", postId, userId);

        // F13 Phase 4-γ: ファイル系添付（IMAGE / VIDEO_FILE）の使用量減算
        ScopeResolution scope = resolveScope(
                post.getScopeType().name(), post.getScopeId(), userId);
        for (TimelinePostAttachmentEntity att : attachments) {
            if (att.getFileSize() == null || att.getFileSize() <= 0) {
                continue;
            }
            AttachmentType type = att.getAttachmentType();
            if (type == AttachmentType.IMAGE || type == AttachmentType.VIDEO_FILE) {
                storageQuotaService.recordDeletion(
                        scope.scopeType(), scope.scopeId(), att.getFileSize(),
                        StorageFeatureType.TIMELINE,
                        REFERENCE_TYPE, att.getId(), userId);
            }
        }
    }

    /**
     * 投稿詳細を取得する。添付ファイル・みたよ！状態・投票を含む。
     *
     * @param postId 投稿ID
     * @param userId 閲覧ユーザーID（みたよ！状態・投票の自分の投票を取得するため）
     * @return 投稿詳細
     */
    public PostDetailResponse getPostDetail(Long postId, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);
        // 認可根治 Wave3-B7-timeline（BOLA 根治）: post を先に取得し、post 自身が持つ scope に対して
        // membership を検証する。不可視なら「存在しない」と同じ POST_NOT_FOUND を返し、
        // 越境アクセスの成否（対象 ID が実在するか）を漏らさない。
        if (!postVisibilityGuard.isVisible(post, userId)) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }

        // issue #2424: 画像添付の image.url/thumbnailUrl を署名付き表示 URL に解決して返す。
        // 添付エンティティを取得し、画像キーをまとめて 1 回 resolveAll（N+1 回避）してから割り当てる。
        List<TimelinePostAttachmentEntity> attachmentEntities =
                attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(postId);
        Map<String, String> imageUrlByKey = resolveImageUrls(attachmentEntities);
        List<AttachmentResponse> attachments = timelineMapper.toAttachmentResponseList(attachmentEntities)
                .stream()
                .map(a -> withResolvedImageUrl(a, imageUrlByKey))
                .toList();

        boolean mitayo = reactionRepository.existsByTimelinePostIdAndUserId(postId, userId);
        int mitayoCount = (int) reactionRepository.countByTimelinePostId(postId);

        PollResponse pollResponse = pollService.getPollByPostId(postId, userId);

        // リプライのプレビュー（会話の古い順＝createdAt 昇順・先頭最大 RECENT_REPLIES_LIMIT 件）を enrich して同梱する。
        // リプライ一覧 API の ID 昇順キーセットページングと一貫させ「古い順・先頭N件」で統一する（最新N件ではない）。
        // enrichPosts を通すことで一覧と同じく著者名/アバター・投稿元名/slug・代理主体が付与される。
        List<PostResponse> recentReplies = enrichPosts(timelineMapper.toPostResponseList(
                postRepository.findRepliesByParentId(postId, PageRequest.of(0, RECENT_REPLIES_LIMIT))));

        return PostDetailResponse.builder()
                .id(post.getId())
                .scope(new PostDetailResponse.PostScopeDto(
                        post.getScopeType().name(),
                        post.getScopeId()))
                .author(new PostDetailResponse.PostAuthorDto(
                        post.getUserId(),
                        post.getSocialProfileId(),
                        post.getPostedAsType().name(),
                        post.getPostedAsId()))
                .content(new PostDetailResponse.PostContentDto(
                        post.getContent(),
                        post.getParentId(),
                        post.getRepostOfId(),
                        post.getStatus().name(),
                        post.getScheduledAt(),
                        post.getIsPinned()))
                .stats(new PostDetailResponse.PostStatsDto(
                        post.getRepostCount(),
                        post.getReactionCount(),
                        post.getReplyCount(),
                        post.getAttachmentCount(),
                        post.getEditCount(),
                        mitayoCount,
                        mitayo))
                .attachments(attachments)
                .poll(pollResponse)
                .audit(new PostDetailResponse.PostAuditDto(
                        post.getCreatedAt(),
                        post.getUpdatedAt()))
                .recentReplies(recentReplies)
                .build();
    }

    /**
     * スコープ別フィードを取得する。
     *
     * <p>scopeType=VILLAGE の場合は scopeVillageId（UUID）で絞り込む。
     * scopeVillageId が null の場合、本メソッド自体は空リストを返すが、
     * <b>EP 全体（{@code GET /timeline/feed}）としては 403 になる</b>。
     * コントローラーが続けて呼ぶ {@link #getPinnedPosts(String, Long, UUID, Long)} が
     * 「村 ID 無しの VILLAGE 指定」を fail-closed で拒否するため（認可根治 Wave6）。
     * FE は VILLAGE スコープでは常に scopeVillageId を送るため正常系に影響はない。</p>
     *
     * <p><b>認可根治 Wave3-B7-timeline</b>: TEAM/ORGANIZATION は {@link #checkScopeMembership}
     * （非メンバーは COMMON_002・403）、VILLAGE は {@link #requireVillageMember}
     * （非メンバーは {@link VillageErrorCode#NOT_MEMBER}・IDOR 対策で 404 相当）で
     * 呼び出し元のメンバーシップを検証する。PUBLIC 等その他スコープは従来通り無検証
     * （本来公開のため）。TIMELINE_POST は {@code VisibilityResolver} 未実装のため、
     * {@code contentVisibilityChecker} ではなく明示 membership チェックで是正する。</p>
     *
     * @param scopeType      スコープ種別
     * @param scopeId        スコープID（VILLAGE スコープでは未使用）
     * @param scopeVillageId 村 ID（scopeType=VILLAGE 時に使用）
     * @param size           取得件数
     * @param userId         呼び出し元ユーザー ID（メンバーシップ検証用）
     * @return 投稿一覧
     */
    public List<PostResponse> getFeed(String scopeType, Long scopeId, UUID scopeVillageId, int size, Long userId) {
        int feedSize = size > 0 ? size : DEFAULT_FEED_SIZE;
        PostScopeType scopeTypeEnum = parseScopeType(scopeType);
        List<TimelinePostEntity> posts;
        if (scopeTypeEnum == PostScopeType.VILLAGE) {
            // 村スコープは scope_village_id（UUID）で絞り込む
            if (scopeVillageId == null) {
                return List.of();
            }
            requireVillageMember(scopeVillageId, userId);
            posts = postRepository.findFeedByVillageId(scopeVillageId, PageRequest.of(0, feedSize));
        } else {
            checkScopeMembership(scopeType, scopeId, userId);
            posts = postRepository.findFeedByScopeType(scopeTypeEnum, scopeId, PageRequest.of(0, feedSize));
        }
        // スコープ別フィードにも著者名/アバター・投稿元名/slug・代理主体を enrich する
        // （マイフィードと同じ enrichPosts を通す）。
        return enrichPosts(timelineMapper.toPostResponseList(posts));
    }

    /**
     * 呼び出し元ユーザーが対象村の現役 USER メンバーであることを検証する（認可根治 Wave3-B7-timeline）。
     * 非メンバーは {@link VillageErrorCode#NOT_MEMBER}（village ドメインの既存 IDOR 対策と同一方針・
     * {@code VillageSearchService#requireVillageMember} を踏襲）。
     */
    private void requireVillageMember(UUID villageId, Long userId) {
        if (!postingIdentityService.isUserVillageMember(villageId, userId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }
    }

    /**
     * リプライ先の親投稿に到達できることを検証する（認可根治 Wave6・書き込み経路）。
     *
     * <p>リプライは親投稿のスコープをそのまま継承して保存されるため、リクエストが申告した
     * スコープではなく <b>継承元の親投稿が属する実効スコープ</b> に対して認可を評価する。
     * 判定は読取経路（{@link #getPostDetail} / {@link #getReplies}）と同じ
     * {@link TimelinePostVisibilityAccessGuard#isVisible} を用い、到達できない場合は読取経路と
     * 同一の {@link TimelineErrorCode#POST_NOT_FOUND} に倒して対象 ID の実在を秘匿する。</p>
     *
     * <p>VILLAGE スコープだけは本メソッドで判定しない。村への投稿権限は下流の
     * {@link PostingIdentityService#validatePostingIdentity} が
     * <b>投稿主体（USER / TEAM / ORGANIZATION）単位</b>で検証しており、ここで呼び出し元
     * {@code userId} 単位の村メンバー判定を重ねると、チーム／組織としての正当な代理投稿の
     * 判定粒度を落とすことになる。素通しではなく、より粒度の細かい主体検証へ委譲する
     * （{@link #doCreatePost} の VILLAGE ブロックがリプライ経路でも必ず走る）。</p>
     *
     * @param parentPost リプライ先の親投稿
     * @param userId     呼び出し元ユーザー ID
     */
    private void requireReplyableParent(TimelinePostEntity parentPost, Long userId) {
        if (parentPost.getScopeType() == PostScopeType.VILLAGE) {
            return;
        }
        if (!postVisibilityGuard.isVisible(parentPost, userId)) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }
    }

    /**
     * 個人ダッシュボード集約タイムライン（マイフィード）を取得する。
     *
     * <p>ログインユーザーが所属する全チーム/組織（MEMBER / SUPPORTER 両方）の
     * タイムライン投稿を横断集約し、新しい順（id 降順）で返す。timeline 投稿に
     * 可視性列は無く所属スコープ一致＝可視のため、サポーターもメンバーと完全同一の
     * 投稿が見える。VILLAGE は集約対象外（殿の確定仕様 b）。自分の投稿も含む（仕様 a）。</p>
     *
     * <p>所属スコープ ID は {@link com.mannschaft.app.membership.service.MembershipService}
     * 経由で解決する（ドメイン境界原則）。両メソッドは MEMBER / SUPPORTER 両方を含む。</p>
     *
     * <p>空ガード: 所属チーム・組織が両方空なら repo を呼ばず空リストを返す
     * （JPQL の {@code IN ()} エラー回避）。片方だけ空の場合は実 scopeId と衝突しない
     * ダミー値（{@code -1L}）で埋め、もう一方の OR 条件だけを実効化する。</p>
     *
     * @param userId 認証ユーザー ID
     * @param cursor カーソル（この投稿 id 未満を取得）。null なら最新から
     * @param limit  取得件数（1 件以上）
     * @return マイフィード投稿一覧（id 降順・最大 limit 件）
     */
    public List<PostResponse> getMyFeed(Long userId, Long cursor, int limit) {
        int feedSize = limit > 0 ? limit : DEFAULT_FEED_SIZE;
        List<Long> teamIds = membershipService.getActiveTeamIdsByUser(userId);
        List<Long> orgIds = membershipService.getActiveOrgIdsByUser(userId);

        // 空ガード: 所属がゼロなら DB を叩かず空（JPQL IN () エラー回避）。
        if (teamIds.isEmpty() && orgIds.isEmpty()) {
            return List.of();
        }

        // 片方だけ空でも JPQL IN :emptyList が DB で問題になりうるため、
        // 実 scopeId（常に正の値）と衝突しないダミー -1L を入れて当該 OR 条件を無効化する。
        List<Long> safeTeamIds = teamIds.isEmpty() ? List.of(-1L) : teamIds;
        List<Long> safeOrgIds = orgIds.isEmpty() ? List.of(-1L) : orgIds;

        // 配下配信: 上位組織が CHILDREN/DESCENDANTS で出した投稿の到達範囲（距離別）。
        TimelineDeliveryScopeResolver.Reach reach = deliveryScopeResolver.resolve(teamIds, orgIds);

        // ミュート除外は SQL 側で行う（アプリ側で捨てると limit 件が目減りしページが歯抜けになる）。
        // NOT IN のダミーは IN と意味が反転する: NOT IN (-1) は「全件通過（ミュートなし）」を意味する。
        List<Long> safeMutedTeamIds = safeNotInList(
                muteRepository.findMutedIdsByUserIdAndMutedType(userId, MUTED_TYPE_TEAM));
        List<Long> safeMutedOrgIds = safeNotInList(
                muteRepository.findMutedIdsByUserIdAndMutedType(userId, MUTED_TYPE_ORGANIZATION));

        List<TimelinePostEntity> posts = postRepository.findMyFeed(
                safeTeamIds, safeOrgIds,
                reach.safeNearOrgIds(), reach.safeFarOrgIds(),
                safeMutedTeamIds, safeMutedOrgIds,
                cursor, PageRequest.of(0, feedSize));
        return enrichPosts(timelineMapper.toPostResponseList(posts));
    }

    /**
     * 投稿群に「投稿元（team/org 名・slug）」「著者（表示名・アバター）」
     * 「代理投稿主体（team/org 名・ロゴ）」をバッチ enrich して付与する。
     *
     * <p>マイフィード・スコープ別フィード・ピン留め一覧・リプライ一覧・投稿詳細の直近リプライの
     * すべてから共通で呼ばれる（myFeed 固有依存は無い）。</p>
     *
     * <p><b>N+1 回避</b>: 全投稿から ID 集合を収集し、名前解決/slug 解決は種別ごとに 1 回だけ呼ぶ
     * （{@link FriendFeedService} の enrich パターンを踏襲）。team/org 名・アイコンは「投稿元スコープ」と
     * 「代理主体」の ID を和集合にして 1 回ずつ解決する。</p>
     *
     * <p><b>null 安全</b>: 退会・匿名化ユーザー／論理削除された team/org は各 Map に含まれないため、
     * 表示名は既定文言（{@value #UNKNOWN_USER_NAME} 等）へフォールバックする（例外を投げない）。
     * postedAsType=USER/SOCIAL_PROFILE の場合は {@code postedAs} を付与せず {@code null} のままとする。
     * 投稿が空（enrich 対象 ID が無い）の場合は解決を一切呼ばずそのまま返す。</p>
     *
     * <p>ドメイン境界: timeline → team/organization/user の参照は Service（NameResolver 等）経由で
     * プリミティブのみを受け取り、Entity を跨いで持ち込まない（新規クロスドメイン FK は作らない）。</p>
     *
     * @param posts マッパー変換済みの投稿レスポンス（生 ID のみ）
     * @return enrich 済みの投稿レスポンス（順序保持）
     */
    private List<PostResponse> enrichPosts(List<PostResponse> posts) {
        if (posts == null || posts.isEmpty()) {
            return posts;
        }

        // 1. ID 集合の収集（投稿元スコープ / 著者 / 代理主体を種別ごとに分ける）
        Set<Long> scopeTeamIds = new HashSet<>();
        Set<Long> scopeOrgIds = new HashSet<>();
        Set<Long> authorUserIds = new HashSet<>();
        Set<Long> postedAsTeamIds = new HashSet<>();
        Set<Long> postedAsOrgIds = new HashSet<>();
        for (PostResponse p : posts) {
            if (p.getScope() != null && p.getScope().scopeId() != null) {
                if (PostScopeType.TEAM.name().equals(p.getScope().scopeType())) {
                    scopeTeamIds.add(p.getScope().scopeId());
                } else if (PostScopeType.ORGANIZATION.name().equals(p.getScope().scopeType())) {
                    scopeOrgIds.add(p.getScope().scopeId());
                }
            }
            if (p.getAuthor() != null) {
                if (p.getAuthor().userId() != null) {
                    authorUserIds.add(p.getAuthor().userId());
                }
                Long paId = p.getAuthor().postedAsId();
                if (paId != null) {
                    if (PostedAsType.TEAM.name().equals(p.getAuthor().postedAsType())) {
                        postedAsTeamIds.add(paId);
                    } else if (PostedAsType.ORGANIZATION.name().equals(p.getAuthor().postedAsType())) {
                        postedAsOrgIds.add(paId);
                    }
                }
            }
        }

        // 2. バッチ解決（種別ごとに 1 回のみ = N+1 回避）
        // 名前・アイコンは投稿元スコープと代理主体の ID を和集合にして重複解決を避ける。
        Set<Long> allTeamIds = new HashSet<>(scopeTeamIds);
        allTeamIds.addAll(postedAsTeamIds);
        Set<Long> allOrgIds = new HashSet<>(scopeOrgIds);
        allOrgIds.addAll(postedAsOrgIds);

        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(allTeamIds);
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(allOrgIds);
        Map<Long, String> teamSlugs = teamService.getSlugsByIds(scopeTeamIds);
        Map<Long, String> orgSlugs = organizationService.getSlugsByIds(scopeOrgIds);
        Map<Long, String> teamIcons = nameResolverService.resolveTeamIconUrls(postedAsTeamIds);
        Map<Long, String> orgIcons = nameResolverService.resolveOrganizationIconUrls(postedAsOrgIds);
        Map<Long, String> authorNames = nameResolverService.resolveUserDisplayNames(authorUserIds);
        Map<Long, String> authorAvatars = nameResolverService.resolveUserAvatarUrls(authorUserIds);

        // 3. 各投稿へ enrich（toBuilder で不変 DTO を再構築）
        // F17.2 Wave2 ①: システム投稿（systemPostType 非 null）は投稿主体・投稿者が存在しないため
        // postedAs を組み立てず null で返す（設計書 §3.9(a)）。system_post_type 非 null 投稿では
        // user_id も NULL なので enrichUser も自然に null を返す。
        List<PostResponse> enriched = posts.stream()
                .map(p -> p.toBuilder()
                        .scope(enrichScope(p.getScope(), teamNames, orgNames, teamSlugs, orgSlugs))
                        .user(enrichUser(p.getAuthor(), authorNames, authorAvatars))
                        .postedAs(p.getSystemPostType() != null
                                ? null
                                : enrichPostedAs(p.getAuthor(), teamNames, orgNames, teamIcons, orgIcons))
                        .build())
                .toList();
        // issue #2424: 一覧（feed）にも添付配列を付与し、画像は署名付き URL で返す。
        return attachFeedAttachments(enriched);
    }

    /**
     * 投稿一覧に添付配列を付与する（issue #2424・feed で画像を表示するための根治）。
     *
     * <p><b>N+1 回避</b>: 全投稿 ID 分の添付を 1 クエリで一括取得し、画像キーの署名解決も
     * 全添付をまとめて {@link MediaUrlResolver#resolveAll} で 1 回だけ行う。取得した添付は
     * {@code timelinePostId} でグルーピングして各投稿へ割り当てる。添付が無い投稿には空配列を
     * 設定する（{@code null} を避け FE の {@code attachments?.length} 分岐を安定させる）。</p>
     *
     * <p><b>認可</b>: 本メソッドは呼び出し元がスコープ検証済みで返す投稿群（{@link #getFeed} /
     * {@link #getMyFeed} 等が membership を検証した結果）に対してのみ添付を積む。可視な投稿の
     * 添付だけを署名するため、独自の可視性述語を新設せずに越境署名を防げる。</p>
     */
    private List<PostResponse> attachFeedAttachments(List<PostResponse> posts) {
        if (posts == null || posts.isEmpty()) {
            return posts;
        }
        List<Long> postIds = posts.stream()
                .map(PostResponse::getId)
                .filter(Objects::nonNull)
                .toList();
        if (postIds.isEmpty()) {
            return posts;
        }
        List<TimelinePostAttachmentEntity> all =
                attachmentRepository.findByTimelinePostIdInOrderByTimelinePostIdAscSortOrderAsc(postIds);
        // 画像キーは投稿をまたいで一括で 1 回だけ署名解決する（N+1 回避）。
        Map<String, String> imageUrlByKey = resolveImageUrls(all);
        Map<Long, List<AttachmentResponse>> byPost = new LinkedHashMap<>();
        for (TimelinePostAttachmentEntity e : all) {
            AttachmentResponse r = withResolvedImageUrl(timelineMapper.toAttachmentResponse(e), imageUrlByKey);
            byPost.computeIfAbsent(e.getTimelinePostId(), k -> new ArrayList<>()).add(r);
        }
        return posts.stream()
                .map(p -> p.toBuilder()
                        .attachments(byPost.getOrDefault(p.getId(), List.of()))
                        .build())
                .toList();
    }

    /**
     * 添付エンティティ群の中から画像（{@link AttachmentType#IMAGE}）の生キーを集めて署名 URL に一括解決する。
     * null/空白キーは除外する。1 回の {@link MediaUrlResolver#resolveAll} で presign を最小化する（N+1 回避）。
     */
    private Map<String, String> resolveImageUrls(Collection<TimelinePostAttachmentEntity> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Map.of();
        }
        List<String> imageKeys = attachments.stream()
                .filter(a -> a.getAttachmentType() == AttachmentType.IMAGE)
                .map(TimelinePostAttachmentEntity::getFileKey)
                .filter(k -> k != null && !k.isBlank())
                .toList();
        return mediaUrlResolver.resolveAll(imageKeys);
    }

    /**
     * 画像添付レスポンスに署名付き表示 URL を割り当てる（画像以外・解決不能キーはそのまま返す）。
     *
     * <p>画像は別サムネイルを持たないため {@code thumbnailUrl} は {@code url} と同一値にする
     * （FE は {@code thumbnailUrl || url} を読む）。imageWidth/imageHeight は Mapper 変換値を保持する。</p>
     */
    private AttachmentResponse withResolvedImageUrl(AttachmentResponse att, Map<String, String> imageUrlByKey) {
        if (att == null || !AttachmentType.IMAGE.name().equals(att.getAttachmentType())) {
            return att;
        }
        String key = att.getFile() != null ? att.getFile().fileKey() : null;
        String url = key != null ? imageUrlByKey.get(key) : null;
        if (url == null) {
            return att;
        }
        Short width = att.getImage() != null ? att.getImage().imageWidth() : null;
        Short height = att.getImage() != null ? att.getImage().imageHeight() : null;
        return att.toBuilder()
                .image(new AttachmentResponse.AttachmentImageDto(width, height, url, url))
                .build();
    }

    /** 投稿元スコープに team/org 名・slug を付与する（TEAM/ORGANIZATION のみ。それ以外は素通し）。 */
    private PostResponse.PostScopeDto enrichScope(PostResponse.PostScopeDto scope,
                                                  Map<Long, String> teamNames, Map<Long, String> orgNames,
                                                  Map<Long, String> teamSlugs, Map<Long, String> orgSlugs) {
        if (scope == null || scope.scopeId() == null) {
            return scope;
        }
        if (PostScopeType.TEAM.name().equals(scope.scopeType())) {
            return new PostResponse.PostScopeDto(scope.scopeType(), scope.scopeId(),
                    teamNames.getOrDefault(scope.scopeId(), UNKNOWN_TEAM_NAME),
                    teamSlugs.get(scope.scopeId()));
        }
        if (PostScopeType.ORGANIZATION.name().equals(scope.scopeType())) {
            return new PostResponse.PostScopeDto(scope.scopeType(), scope.scopeId(),
                    orgNames.getOrDefault(scope.scopeId(), UNKNOWN_ORG_NAME),
                    orgSlugs.get(scope.scopeId()));
        }
        return scope;
    }

    /** 著者ユーザー（表示名・アバター）を付与する。userId が無ければ null。 */
    private PostResponse.PostUserDto enrichUser(PostResponse.PostAuthorDto author,
                                                Map<Long, String> authorNames, Map<Long, String> authorAvatars) {
        if (author == null || author.userId() == null) {
            return null;
        }
        Long uid = author.userId();
        return new PostResponse.PostUserDto(
                uid,
                authorNames.getOrDefault(uid, UNKNOWN_USER_NAME),
                authorAvatars.get(uid));
    }

    /** 代理投稿主体（team/org 名・ロゴ）を付与する。postedAsType=USER/SOCIAL_PROFILE は null。 */
    private PostResponse.PostPostedAsDto enrichPostedAs(PostResponse.PostAuthorDto author,
                                                        Map<Long, String> teamNames, Map<Long, String> orgNames,
                                                        Map<Long, String> teamIcons, Map<Long, String> orgIcons) {
        if (author == null || author.postedAsId() == null) {
            return null;
        }
        Long paId = author.postedAsId();
        if (PostedAsType.TEAM.name().equals(author.postedAsType())) {
            String name = teamNames.getOrDefault(paId, UNKNOWN_TEAM_NAME);
            return new PostResponse.PostPostedAsDto("TEAM", paId, name, name, teamIcons.get(paId), null, null);
        }
        if (PostedAsType.ORGANIZATION.name().equals(author.postedAsType())) {
            String name = orgNames.getOrDefault(paId, UNKNOWN_ORG_NAME);
            return new PostResponse.PostPostedAsDto("ORGANIZATION", paId, name, name, orgIcons.get(paId), null, null);
        }
        return null;
    }

    /**
     * ユーザーの投稿一覧を取得する（呼び出し元から可視な scope のみ。認可根治 Wave3-B7-timeline）。
     *
     * <p>旧実装は対象ユーザーの全 PUBLISHED 投稿を scope 無視で返しており、TEAM/PERSONAL 等
     * 呼び出し元が非メンバーの投稿まで漏洩していた（BOLA）。本人が閲覧する場合は scope 不問で
     * 全件、他人が閲覧する場合は PUBLIC + 呼び出し元が所属する TEAM/ORGANIZATION/VILLAGE scope の
     * 投稿のみに限定する（{@link TimelinePostRepository#findByUserIdVisibleToCaller} 参照）。</p>
     *
     * @param targetUserId 投稿一覧の対象ユーザーID
     * @param size         取得件数
     * @param callerUserId 呼び出し元ユーザー ID（可視 scope 解決用）
     * @return 投稿一覧
     */
    public List<PostResponse> getUserPosts(Long targetUserId, int size, Long callerUserId) {
        int feedSize = size > 0 ? size : DEFAULT_FEED_SIZE;
        List<Long> teamIds = membershipService.getActiveTeamIdsByUser(callerUserId);
        List<Long> orgIds = membershipService.getActiveOrgIdsByUser(callerUserId);
        List<UUID> villageIds = postingIdentityService.getActiveVillageIdsByUser(callerUserId);
        // 空リストは JPQL の IN () で構文エラーになるためダミー値で埋める（findMyFeed と同一規約）。
        List<Long> safeTeamIds = teamIds.isEmpty() ? List.of(-1L) : teamIds;
        List<Long> safeOrgIds = orgIds.isEmpty() ? List.of(-1L) : orgIds;
        List<UUID> safeVillageIds = villageIds.isEmpty() ? List.of(NIL_VILLAGE_ID_SENTINEL) : villageIds;
        // 配下配信の対称性: フィードに出る投稿はユーザー投稿一覧でも到達できなければならない。
        TimelineDeliveryScopeResolver.Reach reach = deliveryScopeResolver.resolve(teamIds, orgIds);
        List<TimelinePostEntity> posts = postRepository.findByUserIdVisibleToCaller(
                targetUserId, callerUserId, safeTeamIds, safeOrgIds,
                reach.safeNearOrgIds(), reach.safeFarOrgIds(), safeVillageIds,
                PageRequest.of(0, feedSize));
        // issue #2424: プロフィール投稿一覧にも添付配列（画像は署名 URL）を付与する。
        return attachFeedAttachments(timelineMapper.toPostResponseList(posts));
    }

    /**
     * 投稿のリプライ一覧をカーソルページネーションで取得する。
     *
     * <p>著者名/アバター・投稿元名/slug・代理主体を {@link #enrichPosts} で付与する
     * （一覧フィードと同一の表示情報）。ID 昇順で並べ、{@code cursor} 指定時はその ID より後を返す。</p>
     *
     * <p><b>認可根治 Wave3-B7-timeline（BOLA 根治）</b>: 親投稿を取得し、その scope に対して
     * {@link TimelinePostVisibilityAccessGuard#isVisible} で可視性を検証する。不可視なら
     * {@link #getPostDetail} と同様に POST_NOT_FOUND を返す（越境アクセスの成否を漏らさない）。</p>
     *
     * @param postId 親投稿ID
     * @param cursor 起点カーソル（この投稿 ID より後を取得）。null なら先頭から
     * @param size   取得件数（1 件以上・0 以下は既定 20）
     * @param userId 呼び出し元ユーザー ID（親投稿の可視性検証用）
     * @return enrich 済みリプライ一覧（ID 昇順）
     */
    public List<PostResponse> getReplies(Long postId, Long cursor, int size, Long userId) {
        TimelinePostEntity parent = findPostOrThrow(postId);
        if (!postVisibilityGuard.isVisible(parent, userId)) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }
        int feedSize = size > 0 ? size : DEFAULT_FEED_SIZE;
        List<TimelinePostEntity> replies = postRepository.findRepliesByParentIdAfterCursor(
                postId, cursor, PageRequest.of(0, feedSize));
        return enrichPosts(timelineMapper.toPostResponseList(replies));
    }

    /**
     * ピン留め投稿一覧を取得する（村スコープ非対応・{@code GET /timeline/pinned} 用）。
     *
     * <p>VILLAGE を指定した場合は {@link #checkScopeMembership} が fail-closed で拒否する。
     * 村のピン留めを取得する場合は
     * {@link #getPinnedPosts(String, Long, UUID, Long)} に村 ID を渡すこと。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    呼び出し元ユーザー ID（メンバーシップ検証用）
     * @return ピン留め投稿一覧
     */
    public List<PostResponse> getPinnedPosts(String scopeType, Long scopeId, Long userId) {
        return getPinnedPosts(scopeType, scopeId, null, userId);
    }

    /**
     * ピン留め投稿一覧を取得する（村スコープ対応）。
     *
     * <p><b>認可根治 Wave3-B7-timeline</b>: TEAM/ORGANIZATION は {@link #checkScopeMembership}
     * で呼び出し元のメンバーシップを検証する（非メンバーは COMMON_002・403）。</p>
     *
     * <p><b>認可根治 Wave6</b>: VILLAGE スコープのピン留めを
     * {@code scope_village_id} で引く経路を新設し、Wave3-B7 で残課題として記録されていた
     * 「{@code scope_id} が常に 0 のため全村のピン留めが種別一致だけで混在する」問題を根治した。
     * 村メンバーであることを {@link #requireVillageMember} で検証したうえで、
     * リポジトリ側でも村 ID を複合キーとして絞る（多層防御）。
     * 村 ID が渡されない VILLAGE 指定は fail-closed（{@link #checkScopeMembership}）。</p>
     *
     * @param scopeType      スコープ種別
     * @param scopeId        スコープID（VILLAGE スコープでは未使用）
     * @param scopeVillageId 村 ID（scopeType=VILLAGE 時に必須）
     * @param userId         呼び出し元ユーザー ID（メンバーシップ検証用）
     * @return ピン留め投稿一覧
     */
    public List<PostResponse> getPinnedPosts(String scopeType, Long scopeId,
                                             UUID scopeVillageId, Long userId) {
        PostScopeType scopeTypeEnum = parseScopeType(scopeType);
        List<TimelinePostEntity> posts;
        if (scopeTypeEnum == PostScopeType.VILLAGE && scopeVillageId != null) {
            requireVillageMember(scopeVillageId, userId);
            posts = postRepository.findPinnedByVillageId(scopeVillageId);
        } else {
            checkScopeMembership(scopeType, scopeId, userId);
            posts = postRepository.findPinnedPosts(scopeTypeEnum, scopeId);
        }
        // ピン留め一覧にも著者名/アバター・投稿元名/slug・代理主体を enrich する。
        return enrichPosts(timelineMapper.toPostResponseList(posts));
    }

    /**
     * 全文検索で投稿を取得する（可視 scope 絞り込み込み。認可根治 Wave3-B7-timeline・本丸）。
     *
     * <p>旧実装は {@code MATCH...AGAINST} のみで scope を一切見ておらず、TEAM/ORGANIZATION/
     * PERSONAL の全投稿がキーワード一致で横断ヒットしていた（本文漏洩）。呼び出し元が可視な
     * scope（PUBLIC 常時 + 所属 TEAM/ORGANIZATION + 自分の PERSONAL）に限定する。
     * VILLAGE は本 Wave では対象外（{@link TimelinePostRepository#SEARCH_QUERY} の Javadoc 参照）。</p>
     *
     * @param keyword 検索キーワード
     * @param limit   取得件数
     * @param userId  呼び出し元ユーザー ID（可視 scope 解決・PERSONAL 一致判定用）
     * @return 検索結果
     */
    public List<PostResponse> searchPosts(String keyword, int limit, Long userId) {
        int searchLimit = limit > 0 ? limit : DEFAULT_FEED_SIZE;
        List<Long> teamIds = membershipService.getActiveTeamIdsByUser(userId);
        List<Long> orgIds = membershipService.getActiveOrgIdsByUser(userId);
        // 空リストは native SQL の IN () で構文エラーになるためダミー値で埋める（findMyFeed と同一規約）。
        List<Long> safeTeamIds = teamIds.isEmpty() ? List.of(-1L) : teamIds;
        List<Long> safeOrgIds = orgIds.isEmpty() ? List.of(-1L) : orgIds;
        // 配下配信の対称性: 配信で届いた投稿は検索でもヒットする。
        // 一方でミュートは検索に適用しない（マスター御裁可。検索は自分から探しに行く行為であり、
        // ミュートは「流れてこないようにする」表示設定であるため）。
        TimelineDeliveryScopeResolver.Reach reach = deliveryScopeResolver.resolve(teamIds, orgIds);
        List<TimelinePostEntity> posts = postRepository.searchByKeyword(
                keyword, safeTeamIds, safeOrgIds,
                reach.safeNearOrgIds(), reach.safeFarOrgIds(), userId, searchLimit);
        // issue #2424: 検索結果一覧にも添付配列（画像は署名 URL）を付与する。
        return attachFeedAttachments(timelineMapper.toPostResponseList(posts));
    }

    /**
     * 投稿のピン留め状態を切り替える。
     *
     * <p><b>認可根治 Wave7</b>: 投稿者本人、または TEAM/ORGANIZATION スコープの ADMIN/DEPUTY_ADMIN が
     * 切り替えできる（{@link TimelinePostAccessGuard#checkCanManage}）。</p>
     *
     * @param postId 投稿ID
     * @param pinned ピン留めするかどうか
     * @param userId ユーザーID
     * @return 更新された投稿
     */
    @Transactional
    public PostResponse togglePin(Long postId, boolean pinned, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);
        postAccessGuard.checkCanManage(userId, post);

        post.setPinned(pinned);
        post = postRepository.save(post);

        log.info("タイムライン投稿ピン留め切替: id={}, pinned={}", postId, pinned);
        return timelineMapper.toPostResponse(post);
    }

    // --- プライベートメソッド ---

    /**
     * 投稿を取得する。存在しない場合は例外をスローする。
     */
    private TimelinePostEntity findPostOrThrow(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND));
    }

    /**
     * 添付ファイルを保存する。
     * VIDEO_FILE 型の場合は R2 に対象オブジェクトが存在することを確認する。
     *
     * <p><b>F13 Phase 4-γ</b>: ファイル系添付（IMAGE / VIDEO_FILE）の INSERT 完了後に
     * {@link StorageQuotaService#checkQuota} と {@link StorageQuotaService#recordUpload} を呼ぶ。
     * VIDEO_FILE はファイルキーが設定されている場合のみ計上する（URL 埋め込み動画は対象外）。</p>
     *
     * @param postId      投稿 ID
     * @param attachments 添付ファイルリスト
     * @param scope       解決済みストレージスコープ
     * @param userId      操作者ユーザー ID
     */
    private void saveAttachments(Long postId, List<CreateAttachmentRequest> attachments,
                                  ScopeResolution scope, Long userId) {
        short order = 0;
        for (CreateAttachmentRequest att : attachments) {
            AttachmentType attachmentType = AttachmentType.valueOf(att.getAttachmentType());

            // VIDEO_FILE の場合、R2 にオブジェクトが存在することを確認
            if (attachmentType == AttachmentType.VIDEO_FILE && att.getFileKey() != null) {
                if (!r2StorageService.objectExists(att.getFileKey())) {
                    log.warn("VIDEO_FILE の R2 オブジェクトが見つからない: key={}", att.getFileKey());
                    throw new BusinessException(TimelineErrorCode.ATTACHMENT_NOT_FOUND_IN_STORAGE);
                }
            }

            // F13 Phase 4-γ: ファイル系（IMAGE/VIDEO_FILE）かつ fileSize 有効の場合、クォータ確認
            if ((attachmentType == AttachmentType.IMAGE || attachmentType == AttachmentType.VIDEO_FILE)
                    && att.getFileSize() != null && att.getFileSize() > 0) {
                try {
                    storageQuotaService.checkQuota(scope.scopeType(), scope.scopeId(), att.getFileSize());
                } catch (StorageQuotaExceededException e) {
                    log.info("タイムライン添付クォータ超過: postId={}, userId={}, scope={}/{}, requested={}",
                            postId, userId, scope.scopeType(), scope.scopeId(), e.getRequestedBytes());
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "ストレージ容量が不足しているためアップロードできません");
                }
            }

            // VIDEO_FILE の場合は videoProcessingStatus を PENDING に設定
            VideoProcessingStatus processingStatus = null;
            if (attachmentType == AttachmentType.VIDEO_FILE) {
                String statusStr = att.getVideoProcessingStatus();
                processingStatus = (statusStr != null)
                        ? VideoProcessingStatus.valueOf(statusStr)
                        : VideoProcessingStatus.PENDING;
            }

            TimelinePostAttachmentEntity entity = TimelinePostAttachmentEntity.builder()
                    .timelinePostId(postId)
                    .attachmentType(attachmentType)
                    .fileKey(att.getFileKey())
                    .originalFilename(att.getOriginalFilename())
                    .fileSize(att.getFileSize())
                    .mimeType(att.getMimeType())
                    .imageWidth(att.getImageWidth())
                    .imageHeight(att.getImageHeight())
                    .videoUrl(att.getVideoUrl())
                    .videoThumbnailUrl(att.getVideoThumbnailUrl())
                    .videoTitle(att.getVideoTitle())
                    .linkUrl(att.getLinkUrl())
                    .ogTitle(att.getOgTitle())
                    .ogDescription(att.getOgDescription())
                    .ogImageUrl(att.getOgImageUrl())
                    .ogSiteName(att.getOgSiteName())
                    .sortOrder(att.getSortOrder() != null ? att.getSortOrder() : order)
                    .videoThumbnailKey(att.getVideoThumbnailKey())
                    .videoDurationSeconds(att.getVideoDurationSeconds())
                    .videoCodec(att.getVideoCodec())
                    .videoWidth(att.getVideoWidth())
                    .videoHeight(att.getVideoHeight())
                    .videoProcessingStatus(processingStatus)
                    .build();
            TimelinePostAttachmentEntity saved = attachmentRepository.save(entity);

            // F13 Phase 4-γ: ファイル系添付のクォータ使用量加算
            if ((attachmentType == AttachmentType.IMAGE || attachmentType == AttachmentType.VIDEO_FILE)
                    && att.getFileSize() != null && att.getFileSize() > 0) {
                storageQuotaService.recordUpload(
                        scope.scopeType(), scope.scopeId(), att.getFileSize(),
                        StorageFeatureType.TIMELINE,
                        REFERENCE_TYPE, saved.getId(), userId);
            }

            order++;
        }
    }

    /**
     * タイムライン投稿のスコープ文字列からストレージスコープを解決する。
     *
     * <ul>
     *     <li>TEAM → TEAM スコープ (scopeId = teams.id)</li>
     *     <li>ORGANIZATION → ORGANIZATION スコープ (scopeId = organizations.id)</li>
     *     <li>PUBLIC / PERSONAL / FRIEND_* / その他 → 投稿者の PERSONAL スコープ</li>
     * </ul>
     *
     * @param scopeTypeStr 投稿スコープ文字列（例: "TEAM"）
     * @param scopeId      スコープ ID
     * @param userId       投稿者ユーザー ID（PERSONAL フォールバック用）
     * @return 解決済みスコープ
     */
    ScopeResolution resolveScope(String scopeTypeStr, Long scopeId, Long userId) {
        PostScopeType postScope;
        try {
            postScope = PostScopeType.valueOf(scopeTypeStr);
        } catch (IllegalArgumentException e) {
            return new ScopeResolution(StorageScopeType.PERSONAL, userId);
        }
        return switch (postScope) {
            case TEAM -> new ScopeResolution(StorageScopeType.TEAM, scopeId);
            case ORGANIZATION -> new ScopeResolution(StorageScopeType.ORGANIZATION, scopeId);
            default -> new ScopeResolution(StorageScopeType.PERSONAL, userId);
        };
    }

    /** 解決されたストレージスコープ。 */
    record ScopeResolution(StorageScopeType scopeType, Long scopeId) {}
}
