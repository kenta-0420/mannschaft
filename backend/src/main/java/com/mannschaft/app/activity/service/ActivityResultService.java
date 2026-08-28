package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.AddParticipantsRequest;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.dto.CreateDraftActivityRequest;
import com.mannschaft.app.activity.dto.DuplicateActivityRequest;
import com.mannschaft.app.activity.dto.PublicActivitySitemapRow;
import com.mannschaft.app.activity.dto.RemoveParticipantsRequest;
import com.mannschaft.app.activity.dto.UpdateActivityRequest;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 活動記録サービス。活動記録のCRUD・参加者管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityResultService {

    /**
     * 実在しないスコープ ID（番兵）。{@code scope_id} は正の値のみを取るため決して一致しない。
     * sitemap クエリで JPQL の {@code IN ()} 生成を避けるためだけに使う。
     */
    private static final long SITEMAP_NO_MATCH_SCOPE_ID = -1L;

    private final ActivityResultRepository resultRepository;
    private final ActivityParticipantRepository participantRepository;
    private final ActivityTemplateService templateService;
    private final ActivityMapper activityMapper;
    private final ObjectMapper objectMapper;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final ActivityScopeAccessGuard scopeAccessGuard;
    private final com.mannschaft.app.common.visibility.MembershipBatchQueryService membershipBatchQueryService;

    /**
     * 活動記録一覧をページング取得する（認証済み経路）。
     *
     * <p><b>CMP-028 Phase B: 可視性の SQL 述語化（歯抜け根治）</b>: 旧実装は 1 ページ分
     * （{@code size=limit}）を無条件取得してから F00 {@link ContentVisibilityChecker} で
     * メモリ上フィルタしており、他人の DRAFT 等が混ざると要求件数より少ない件数しか
     * 返らない「ページング歯抜け」があった（AC-6）。総件数も上界近似だった（AC-7）。</p>
     *
     * <p>本メソッドは {@code MembershipBatchQueryService#resolveVisibleLevels} が返す
     * 「行を見ずに判定できる可視 {@code StandardVisibility} 集合」を
     * {@link com.mannschaft.app.common.visibility.mapping.ActivityVisibilityMapper#toFunctional}
     * で {@link ActivityVisibility} 集合へ逆写像し、SQL の {@code WHERE visibility IN (...)}
     * へ渡す（{@link ActivityVisibility} は 2 値のみで行依存値を持たないため歯抜けが
     * 数学的にゼロになる）。DRAFT は F00 の status 軸と同じ意味論
     * （作成者本人 or SystemAdmin のみ可視）を SQL 上でも同一述語として再現する。
     * 判定器は F00 のまま 1 つ（新しい判定器を作らない）。</p>
     */
    public Page<ActivityResultEntity> listActivities(Long userId, ActivityScopeType scopeType, Long scopeId,
                                                      Long templateId, Pageable pageable) {
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, scopeType, scopeId);

        com.mannschaft.app.common.visibility.ScopeKey scope =
                new com.mannschaft.app.common.visibility.ScopeKey(scopeType.name(), scopeId);
        com.mannschaft.app.common.visibility.UserScopeRoleSnapshot snapshot =
                membershipBatchQueryService.snapshotForUser(userId, Set.of(scope), Set.of(scope));
        Set<com.mannschaft.app.common.visibility.StandardVisibility> visibleLevels =
                membershipBatchQueryService.resolveVisibleLevels(scope, snapshot);
        Set<ActivityVisibility> visibleVisibilities =
                com.mannschaft.app.common.visibility.mapping.ActivityVisibilityMapper.toFunctional(visibleLevels);
        // StandardVisibility.PUBLIC は常に visibleLevels に含まれ、ActivityVisibility.PUBLIC へ
        // 必ず逆写像されるため visibleVisibilities は非空（IN () の不正 SQL は起きない）。

        Page<ActivityResultEntity> page = templateId != null
                ? resultRepository.findVisibleByScopeTypeAndScopeIdAndTemplateId(
                        scopeType, scopeId, templateId, visibleVisibilities,
                        userId, snapshot.isSystemAdmin(), pageable)
                : resultRepository.findVisibleByScopeTypeAndScopeId(
                        scopeType, scopeId, visibleVisibilities,
                        userId, snapshot.isSystemAdmin(), pageable);

        // 第二の門（保険）: SQL 述語と F00 の判定が食い違った場合を検知する。
        // 通常は 1 件も落ちない。乖離した場合は fail-closed で除外し警告を残す
        // （listPublicActivities の「第二の門」と同じ流儀）。
        List<ActivityResultEntity> content = page.getContent();
        if (content.isEmpty()) {
            return page;
        }
        Set<Long> accessibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.ACTIVITY_RESULT,
                content.stream().map(ActivityResultEntity::getId).collect(Collectors.toSet()),
                userId);
        if (accessibleIds.size() == content.size()) {
            return page;
        }
        List<Long> divergentIds = content.stream()
                .map(ActivityResultEntity::getId)
                .filter(id -> !accessibleIds.contains(id))
                .toList();
        log.warn("認証済み活動記録一覧: SQL 述語と F00 可視性判定が乖離しました"
                        + "（fail-closed で除外）。scopeType={}, scopeId={}, userId={}, divergentIds={}",
                scopeType, scopeId, userId, divergentIds);
        List<ActivityResultEntity> filtered = content.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .collect(Collectors.toList());
        return new PageImpl<>(filtered, pageable,
                Math.max(0L, page.getTotalElements() - divergentIds.size()));
    }

    /**
     * 公開活動記録一覧をページング取得する（匿名公開経路）。
     *
     * <h2>ページング歯抜けの根治（契約テスト AC-30 / AC-31 / AC-31b / AC-35）</h2>
     * <p>旧実装は {@code visibility} / {@code status} 条件を<b>持たない</b> SQL で
     * スコープ配下から 1 ページ分（{@code size=limit}）を取得し、<b>取得後にメモリ上で</b>
     * {@link ContentVisibilityChecker#filterAccessible(ReferenceType, java.util.Collection, Long)}
     * を掛けていた。{@code Pageable} は既に {@code size=limit} で切られているため落ちた分は
     * 補充されず、非公開（{@code MEMBERS_ONLY} / {@code DRAFT}）が混在すると
     * <b>{@code limit=20} を要求しても 20 件返らない</b>という歯抜けが起きていた。
     * 総件数も「全行数 − このページで落ちた件数」という上界近似にしかならなかった。</p>
     *
     * <p>絞り込みを <b>SQL 段</b>（{@link ActivityResultRepository#findPublicByScopeTypeAndScopeId}）
     * へ降ろすことで、要求件数ちょうどが返り、総件数も実公開件数と一致する。</p>
     *
     * <h2>なぜ SQL に可視性条件を書いてよいのか（F00 一本化方針との関係）</h2>
     * <p>「可視性判定は F00 に一本化する」という方針の実体は
     * <b>「二つ目の判定器を作るな／手書きのロール階層を書くな」</b>であって
     * 「SQL に書くな」ではない。設計書
     * {@code docs/features/F02.6_announcement_widget.md} はむしろ
     * 「検証は Repository 層の {@code @Query} レベルで WHERE 句に入れる
     * （Service 層の if 文に依存しない）」と規定している。</p>
     *
     * <p>金型である {@code PublicPostQueryService}（F19.1・{@code PublicActivityQueryService}
     * 自身が「金型」と明記）は、一覧を
     * {@code BlogPostRepository#findPublicPostsByTeamId}（{@code visibility = PUBLIC AND
     * status = PUBLISHED}）という SQL 述語で解いており、一覧経路で {@code filterAccessible}
     * を呼んでいない。{@code TournamentService#listPublicTournaments} も同様。
     * {@code AnnouncementFeedVisibilityResolver} は「一覧は SQL 述語・単件は F00 Resolver」の
     * 併存を公式に容認している。</p>
     *
     * <p><b>匿名では F00 のラダーが縮退するため、SQL 述語は F00 自身の宣言の機械的転写になる</b>:
     * {@code MembershipBatchQueryService#snapshotForUser} は {@code userId == null} で
     * {@code UserScopeRoleSnapshot.empty()} を返し、
     * {@link com.mannschaft.app.common.visibility.StandardVisibility#PUBLIC} の Javadoc が
     * 「未認証時は本値かつ PUBLISHED のときのみ true、それ以外の値はすべて fail-closed」と
     * 明文で宣言している。</p>
     *
     * <h2>F00 は「第二の門」として残す</h2>
     * <p>SQL が通した行を F00 で再確認する。通常は 1 件も落ちない。
     * <b>落ちた場合は乖離であり {@code log.warn} に記録する</b>
     * （SQL 述語と F00 の判定が食い違ったという本番検知点）。乖離した行は
     * <b>返さない</b>（fail-closed を維持）うえ、総件数からも差し引く。</p>
     *
     * <p>「SQL 述語の集合」と「F00 の判定集合」が厳密一致し続けることは、契約テスト
     * <b>AC-32（等価性番人）</b>が {@code visibility × status × deleted} の全 8 組合せで
     * 機械的に固定している。片方だけを変更すると必ず落ちる。</p>
     *
     * <p><b>注意</b>: 本メソッドは親スコープ（チーム / 組織）の公開性を検証<b>しない</b>。
     * 匿名公開経路では {@code PublicActivityQueryService} が親スコープを先に検証すること。</p>
     */
    public Page<ActivityResultEntity> listPublicActivities(ActivityScopeType scopeType, Long scopeId,
                                                            Pageable pageable) {
        // 門2: visibility=PUBLIC かつ status=PUBLISHED を SQL の WHERE 句で絞る
        // （論理削除は @SQLRestriction("deleted_at IS NULL") が自動除外）。
        Page<ActivityResultEntity> page =
                resultRepository.findPublicByScopeTypeAndScopeId(scopeType, scopeId, pageable);
        List<ActivityResultEntity> content = page.getContent();

        if (content.isEmpty()) {
            return page;
        }

        // 門3（第二の門）: F00 ContentVisibilityChecker で再確認（userId=null = 未認証）。
        // ID 集合の 1 回のバッチ呼び出しなので件数に比例した SQL は発行されない（N+1 禁止・AC-34）。
        Set<Long> accessibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.ACTIVITY_RESULT,
                content.stream().map(ActivityResultEntity::getId).collect(Collectors.toSet()),
                null);

        if (accessibleIds.size() == content.size()) {
            // 正常系: SQL 述語と F00 の判定が一致。DB が算出した総件数・ページ情報をそのまま返す。
            return page;
        }

        // 乖離検知: SQL 述語が通したのに F00 が拒否した行がある = 両者の定義がずれている。
        // fail-closed（返さない）を維持しつつ、本番で気付けるよう警告を残す。
        List<Long> divergentIds = content.stream()
                .map(ActivityResultEntity::getId)
                .filter(id -> !accessibleIds.contains(id))
                .toList();
        log.warn("公開活動記録一覧: SQL 述語(visibility=PUBLIC AND status=PUBLISHED)と "
                        + "F00 可視性判定が乖離しました（fail-closed で除外）。"
                        + "scopeType={}, scopeId={}, divergentIds={}",
                scopeType, scopeId, divergentIds);

        List<ActivityResultEntity> filtered = content.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .collect(Collectors.toList());
        return new PageImpl<>(filtered, pageable,
                Math.max(0L, page.getTotalElements() - divergentIds.size()));
    }

    /**
     * 公開用に活動記録詳細を取得する（認証不要・メンバーシップチェックなし）。
     * ActivityPublicController 等の公開エンドポイント専用。
     */
    public ActivityResultEntity getActivity(Long id) {
        return findActivityOrThrow(id);
    }

    /**
     * 活動記録詳細を取得する。
     */
    public ActivityResultEntity getActivity(Long id, Long userId) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // スコープメンバーシップ検証
        scopeAccessGuard.checkMembership(userId, entity.getScopeType(), entity.getScopeId());
        // AC-10: DRAFT（下書き）は作成者本人（または管理者以上）のみ閲覧可。
        // それ以外の会員には「存在しない」ものとして扱う（ACTIVITY_NOT_FOUND で漏洩防止）。
        if (entity.getStatus() == com.mannschaft.app.activity.ActivityStatus.DRAFT
                && !userId.equals(entity.getCreatedBy())) {
            boolean adminOrAbove = scopeAccessGuard.isAdminOrAbove(
                    userId, entity.getScopeType(), entity.getScopeId());
            if (!adminOrAbove) {
                throw new BusinessException(ActivityErrorCode.ACTIVITY_NOT_FOUND);
            }
        }
        return entity;
    }

    /**
     * 公開活動記録を ID で取得する（スコープ不問）。
     *
     * <p>F06.4 SNS シェア用。フロントエンドがスコープ（team/org）を意識せずに
     * ID 直引きで公開済みの記録を取得するために使用する。</p>
     *
     * <p><b>status 条件は必須</b>: 旧実装は {@code findByIdAndVisibility(id, PUBLIC)} のみで
     * status を見ておらず、{@code visibility=PUBLIC} のまま公開していない下書き
     * （{@code status=DRAFT}）が匿名で読めてしまっていた（契約テスト AC-11）。
     * 論理削除済みは {@code @SQLRestriction("deleted_at IS NULL")} が自動除外する。</p>
     *
     * <p><b>注意</b>: 本メソッドは親スコープ（チーム / 組織）の公開性を検証<b>しない</b>。
     * 匿名公開経路では {@code PublicActivityQueryService} 経由で使うこと。</p>
     *
     * @param id 活動記録 ID
     * @return visibility=PUBLIC かつ status=PUBLISHED の活動記録（該当なしは空）
     */
    public Optional<ActivityResultEntity> findPublicActivityById(Long id) {
        return resultRepository.findByIdAndVisibilityAndStatus(
                id, ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED);
    }

    /**
     * F06.4 sitemap.xml 用 — 親スコープが公開である公開活動記録を全件取得する。
     *
     * <p>publicview ドメイン（{@code SitemapQueryService}）から呼ばれる<b>唯一の入口</b>。
     * 越境する型を増やさないため、戻り値は JDK 標準型だけの
     * {@link PublicActivitySitemapRow} に詰め替えて返す（Entity は外へ出さない）。</p>
     *
     * <p><b>親スコープの公開判定は呼び出し元が行う</b>: 「どのチーム / 組織が公開か」は
     * team / organization ドメインしか知り得ない知識であり、activity ドメインから
     * それらの Repository を引くのは番人 D-5 違反になる。よって本メソッドは
     * <b>公開スコープ ID 集合を引数で受け取る</b>形にし、判定の責務を
     * 既に両ドメインを束ねている {@code SitemapQueryService} 側へ置いている。</p>
     *
     * <p><b>空集合の扱い</b>: JPQL の {@code IN :ids} に空コレクションを渡すと
     * {@code IN ()} という不正な SQL になる DB がある。公開チームだけ存在して公開組織が
     * 0 件、という状況は普通に起きるため、空集合は<b>実在しない番兵 ID</b>
     * （{@value #SITEMAP_NO_MATCH_SCOPE_ID}。ID は正の AUTO_INCREMENT なので決して一致しない）
     * に差し替えてから渡す。両方とも空なら SQL を撃たずに空リストを返す。</p>
     *
     * @param publicTeamIds         公開チームの ID 集合（空可）
     * @param publicOrganizationIds 公開組織の ID 集合（空可）
     * @return sitemap に載せてよい活動記録の行（該当なしは空リスト）
     */
    public List<PublicActivitySitemapRow> findPublicActivitiesForSitemap(
            Collection<Long> publicTeamIds, Collection<Long> publicOrganizationIds) {
        boolean noTeams = publicTeamIds == null || publicTeamIds.isEmpty();
        boolean noOrgs = publicOrganizationIds == null || publicOrganizationIds.isEmpty();
        if (noTeams && noOrgs) {
            // 公開スコープが 1 つも無い＝載せてよい記録も存在しない。SQL を撃つ必要すらない。
            return List.of();
        }
        return resultRepository.findPublicForSitemap(
                        orSentinel(publicTeamIds), orSentinel(publicOrganizationIds)).stream()
                .map(e -> new PublicActivitySitemapRow(e.getId(), e.getUpdatedAt()))
                .toList();
    }

    /** 空コレクションを「決して一致しない番兵 1 件」に差し替える（{@code IN ()} 回避）。 */
    private static Collection<Long> orSentinel(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of(SITEMAP_NO_MATCH_SCOPE_ID);
        }
        return ids;
    }

    /**
     * 活動記録を作成する。
     */
    @Transactional
    public ActivityResultEntity createActivity(Long userId, ActivityScopeType scopeType,
                                                Long scopeId, CreateActivityRequest request) {
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, scopeType, scopeId);
        // テンプレート存在チェック
        templateService.findTemplateOrThrow(request.getTemplateId());
        // 従来経路（createActivity）は作成即公開（status=PUBLISHED, Entity の @Builder.Default）

        // 時刻バリデーション
        if (request.getActivityTimeStart() != null && request.getActivityTimeEnd() != null
                && request.getActivityTimeEnd().isBefore(request.getActivityTimeStart())) {
            throw new BusinessException(ActivityErrorCode.INVALID_TIME_RANGE);
        }

        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : ActivityVisibility.MEMBERS_ONLY;

        String fieldValuesJson = serializeFieldValues(request.getFieldValues());
        String attachmentsJson = serializeAttachments(request.getFileIds());

        ActivityResultEntity entity = ActivityResultEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .templateId(request.getTemplateId())
                .title(request.getTitle())
                .activityDate(request.getActivityDate())
                .activityTimeStart(request.getActivityTimeStart())
                .activityTimeEnd(request.getActivityTimeEnd())
                .description(request.getDescription())
                .fieldValues(fieldValuesJson)
                .attachments(attachmentsJson)
                .visibility(visibility)
                .scheduleId(request.getScheduleId())
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(entity);

        // 参加者の登録
        if (request.getParticipantUserIds() != null && !request.getParticipantUserIds().isEmpty()) {
            for (Long participantUserId : request.getParticipantUserIds()) {
                ActivityParticipantEntity participant = ActivityParticipantEntity.builder()
                        .activityResultId(saved.getId())
                        .userId(participantUserId)
                        .build();
                participantRepository.save(participant);
            }
        }

        log.info("活動記録作成: activityId={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 下書き（DRAFT）活動記録を作成する（F06.4 下書き対応）。
     *
     * <p>AC-8: title + activityDate のみの最小項目で作成できる。テンプレートは任意。
     * status は {@link com.mannschaft.app.activity.ActivityStatus#DRAFT}。DRAFT は
     * 作成者・SystemAdmin のみ閲覧可（F00 可視性で status=DRAFT が author 限定になる）。</p>
     */
    @Transactional
    public ActivityResultEntity createDraftActivity(Long userId, ActivityScopeType scopeType,
                                                    Long scopeId, CreateDraftActivityRequest request) {
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, scopeType, scopeId);
        // テンプレートは任意。指定された場合のみ存在チェック。
        if (request.getTemplateId() != null) {
            templateService.findTemplateOrThrow(request.getTemplateId());
        }

        // 時刻バリデーション
        if (request.getActivityTimeStart() != null && request.getActivityTimeEnd() != null
                && request.getActivityTimeEnd().isBefore(request.getActivityTimeStart())) {
            throw new BusinessException(ActivityErrorCode.INVALID_TIME_RANGE);
        }

        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : ActivityVisibility.MEMBERS_ONLY;

        ActivityResultEntity entity = ActivityResultEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .templateId(request.getTemplateId())
                .title(request.getTitle())
                .activityDate(request.getActivityDate())
                .activityTimeStart(request.getActivityTimeStart())
                .activityTimeEnd(request.getActivityTimeEnd())
                .description(request.getDescription())
                .fieldValues(serializeFieldValues(request.getFieldValues()))
                .visibility(visibility)
                .status(com.mannschaft.app.activity.ActivityStatus.DRAFT)
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(entity);
        log.info("活動記録(下書き)作成: activityId={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 下書き活動記録を公開する（DRAFT → PUBLISHED）。
     *
     * <p>AC-9: publish EP で DRAFT→PUBLISHED。既に PUBLISHED のものを publish すると
     * {@link ActivityErrorCode#INVALID_ACTIVITY_STATUS}（400）。
     * 認可は作成者本人または管理者以上（update/delete と同一境界）。</p>
     */
    @Transactional
    public ActivityResultEntity publishActivity(Long id, Long userId) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // 本人または管理者のみ公開可能（update/delete と同一境界）
        scopeAccessGuard.checkAuthorOrAdmin(
                userId, entity.getCreatedBy(), entity.getScopeType(), entity.getScopeId());
        if (!entity.isPublishable()) {
            // 既に PUBLISHED（DRAFT 以外）の状態からの publish は不正
            throw new BusinessException(ActivityErrorCode.INVALID_ACTIVITY_STATUS);
        }
        entity.publish();
        ActivityResultEntity saved = resultRepository.save(entity);
        log.info("活動記録公開: activityId={}", id);
        return saved;
    }

    /**
     * 活動記録を更新する。
     */
    @Transactional
    public ActivityResultEntity updateActivity(Long id, Long userId, UpdateActivityRequest request) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // 本人または管理者のみ更新可能
        scopeAccessGuard.checkAuthorOrAdmin(
                userId, entity.getCreatedBy(), entity.getScopeType(), entity.getScopeId());

        // 時刻バリデーション
        if (request.getActivityTimeStart() != null && request.getActivityTimeEnd() != null
                && request.getActivityTimeEnd().isBefore(request.getActivityTimeStart())) {
            throw new BusinessException(ActivityErrorCode.INVALID_TIME_RANGE);
        }

        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : entity.getVisibility();

        String fieldValuesJson = serializeFieldValues(request.getFieldValues());
        String attachmentsJson = serializeAttachments(request.getFileIds());

        entity.update(request.getTitle(), request.getActivityDate(),
                request.getActivityTimeStart(), request.getActivityTimeEnd(),
                request.getDescription(), fieldValuesJson, attachmentsJson, visibility);

        ActivityResultEntity saved = resultRepository.save(entity);
        log.info("活動記録更新: activityId={}", id);
        return saved;
    }

    /**
     * 活動記録を論理削除する。
     */
    @Transactional
    public void deleteActivity(Long id, Long userId) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // 本人または管理者のみ削除可能
        scopeAccessGuard.checkAuthorOrAdmin(
                userId, entity.getCreatedBy(), entity.getScopeType(), entity.getScopeId());
        entity.softDelete();
        resultRepository.save(entity);
        log.info("活動記録削除: activityId={}", id);
    }

    /**
     * 活動記録を複製する。
     */
    @Transactional
    public ActivityResultEntity duplicateActivity(Long id, Long userId, DuplicateActivityRequest request) {
        ActivityResultEntity original = findActivityOrThrow(id);
        // スコープメンバーシップ検証: 非メンバーは403（他スコープ会員による複製=IDOR を封じる）
        scopeAccessGuard.checkMembership(userId, original.getScopeType(), original.getScopeId());

        String title = request != null && request.getTitle() != null
                ? request.getTitle() : original.getTitle();
        LocalDate activityDate = request != null && request.getActivityDate() != null
                ? request.getActivityDate() : LocalDate.now(TimezoneContextHolder.get());

        ActivityResultEntity copy = ActivityResultEntity.builder()
                .scopeType(original.getScopeType())
                .scopeId(original.getScopeId())
                .templateId(original.getTemplateId())
                .title(title)
                .activityDate(activityDate)
                .activityTimeStart(original.getActivityTimeStart())
                .activityTimeEnd(original.getActivityTimeEnd())
                .description(original.getDescription())
                .fieldValues(original.getFieldValues())
                .visibility(original.getVisibility())
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(copy);

        // 参加者のコピー
        List<ActivityParticipantEntity> originalParticipants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(id);
        for (ActivityParticipantEntity p : originalParticipants) {
            ActivityParticipantEntity participantCopy = ActivityParticipantEntity.builder()
                    .activityResultId(saved.getId())
                    .userId(p.getUserId())
                    .roleLabel(p.getRoleLabel())
                    .build();
            participantRepository.save(participantCopy);
        }

        log.info("活動記録複製: originalId={}, newId={}", id, saved.getId());
        return saved;
    }

    /**
     * 参加者を追加する。
     */
    @Transactional
    public List<ActivityParticipantResponse> addParticipants(Long activityId, Long userId, AddParticipantsRequest request) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, activity.getScopeType(), activity.getScopeId());

        for (Long participantUserId : request.getUserIds()) {
            // 重複チェック
            if (participantRepository.findByActivityResultIdAndUserId(activityId, participantUserId).isPresent()) {
                continue;
            }

            String roleLabel = null;
            if (request.getRoleLabels() != null) {
                roleLabel = request.getRoleLabels().get(String.valueOf(participantUserId));
            }

            ActivityParticipantEntity participant = ActivityParticipantEntity.builder()
                    .activityResultId(activityId)
                    .userId(participantUserId)
                    .roleLabel(roleLabel)
                    .build();
            participantRepository.save(participant);
        }

        List<ActivityParticipantEntity> participants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId);
        return activityMapper.toParticipantResponseList(participants);
    }

    /**
     * 参加者を削除する。
     */
    @Transactional
    public List<ActivityParticipantResponse> removeParticipants(Long activityId, Long userId, RemoveParticipantsRequest request) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, activity.getScopeType(), activity.getScopeId());
        participantRepository.deleteByActivityResultIdAndUserIdIn(activityId, request.getUserIds());
        log.info("参加者削除: activityId={}, count={}", activityId, request.getUserIds().size());

        List<ActivityParticipantEntity> participants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId);
        return activityMapper.toParticipantResponseList(participants);
    }

    /**
     * 活動記録エンティティを取得する。存在しない場合は例外をスローする。
     */
    ActivityResultEntity findActivityOrThrow(Long id) {
        return resultRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.ACTIVITY_NOT_FOUND));
    }

    private String serializeFieldValues(Map<String, Object> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(fieldValues);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("field_valuesのシリアライズに失敗しました", e);
        }
    }

    private String serializeAttachments(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of("file_ids", fileIds));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("attachmentsのシリアライズに失敗しました", e);
        }
    }
}
