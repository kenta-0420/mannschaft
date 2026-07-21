package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.util.SlugGenerator;
import com.mannschaft.app.common.util.SlugValidator;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.entity.OrganizationSlugHistoryEntity;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.repository.OrganizationSlugHistoryRepository;
import com.mannschaft.app.common.dto.SlugAvailabilityResponse;
import com.mannschaft.app.common.dto.SlugResolveResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.organization.dto.AncestorsResponse;
import com.mannschaft.app.organization.dto.ChildrenResponse;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.dto.OrgAllMembersResponse;
import com.mannschaft.app.organization.dto.OrgTeamSummaryResponse;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.dto.OrganizationSummaryResponse;
import com.mannschaft.app.organization.dto.UpdateOrganizationRequest;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.organization.event.OrganizationCreatedEvent;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 組織管理サービス（ファサード）。
 *
 * <p>組織自身の CRUD・アーカイブ・検索・復元・監査イベント発行を直接担い、
 * メンバー / フォロー / 所属チーム参照は {@link OrganizationMembershipService} へ、
 * 祖先・子組織の階層参照は {@link OrganizationHierarchyService} へ委譲する。
 * Controller・テストからの呼び出し interface は分割前と完全に同一。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationSlugHistoryRepository organizationSlugHistoryRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final InviteTokenRepository inviteTokenRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrganizationMembershipService organizationMembershipService;
    private final OrganizationHierarchyService organizationHierarchyService;
    private final MembershipService membershipService;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * 組織を作成し、作成者をADMINロールで紐付ける。
     */
    @Transactional
    // TODO: OrganizationドメインとAuthドメイン・Roleドメインをまたいでいる。将来はOrganizationCreatedEventで分離予定
    public ApiResponse<OrganizationResponse> createOrganization(Long userId, CreateOrganizationRequest req) {
        // 組織名の重複チェック
        if (organizationRepository.existsByName(req.getName())) {
            throw new BusinessException(OrgErrorCode.ORG_002);
        }

        String slug = resolveSlugForCreate(req.getSlug(), req.getName());
        OrganizationEntity org = OrganizationEntity.builder()
                .name(req.getName())
                .slug(slug)
                .orgType(OrganizationEntity.OrgType.valueOf(req.getOrgType()))
                .prefecture(req.getPrefecture())
                .city(req.getCity())
                .visibility(req.getVisibility() != null
                        ? OrganizationEntity.Visibility.valueOf(req.getVisibility())
                        : OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .parentOrganizationId(req.getParentOrganizationId())
                .supporterEnabled(false)
                .build();
        organizationRepository.save(org);

        // 作成者をADMINロールで紐付ける
        RoleEntity adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_005));
        UserRoleEntity userRole = UserRoleEntity.builder()
                .userId(userId)
                .roleId(adminRole.getId())
                .organizationId(org.getId())
                .build();
        userRoleRepository.save(userRole);

        // F00.5 認可基盤根治: memberships にも MEMBER として入会させる。
        // 認可（AccessControlService.isMember）は memberships を真実の源とするため、
        // user_roles だけでは作成者本人が自組織から 403 で締め出される構造的欠陥を防ぐ。
        // 権限ロール（ADMIN）は user_roles が担い、membership は在籍有無のみ表す（role_kind=MEMBER）。
        MembershipCreateRequest membershipReq = new MembershipCreateRequest();
        membershipReq.setUserId(userId);
        membershipReq.setScopeType(ScopeType.ORGANIZATION);
        membershipReq.setScopeId(org.getId());
        membershipReq.setRoleKind(RoleKind.MEMBER);
        membershipReq.setSource("ORG_CREATE");
        membershipService.join(membershipReq);

        // 監査ログ用イベント発行
        eventPublisher.publishEvent(new OrganizationCreatedEvent(userId, org.getId(), org.getName()));

        log.info("組織作成完了: orgId={}, userId={}", org.getId(), userId);
        return ApiResponse.of(toResponse(org, 1));
    }

    /**
     * 組織を slug（URL識別子）で取得する。
     *
     * <p>Phase 4-E: Valkey にて 10 分キャッシュ。更新・削除時に自動無効化される。</p>
     */
    @Cacheable(value = "org-detail", key = "#slug")
    public ApiResponse<OrganizationResponse> getOrganization(String slug) {
        OrganizationEntity org = findOrganizationBySlugOrThrow(slug);
        Long orgId = org.getId();
        int memberCount = (int) userRoleRepository.countByOrganizationId(orgId);
        return ApiResponse.of(toResponse(org, memberCount));
    }

    /**
     * slug から内部 BIGINT ID を解決する（Controller から他の Service メソッドに渡す用）。
     *
     * @param slug URL 識別子（カスタムスラッグ）
     * @return 内部 BIGINT ID
     */
    public Long resolveOrgId(String slug) {
        return findOrganizationBySlugOrThrow(slug).getId();
    }

    /**
     * 組織がサポーター受け入れを有効化していることを表明する。
     *
     * <p>{@code supporter_enabled} は「この組織がサポーター登録を受け付けるか」を表す
     * 運営者の意思表示であり、フロントエンドも本フラグでフォローボタンの表示を切り替えている
     * （{@code OrgPageHeader.vue}）。サーバ側でも同じ契約を強制し、無効化中の組織への
     * サポーター自己登録を {@code MEMBERSHIP_SUPPORTER_DISABLED}（403）で拒否する。</p>
     *
     * <p>チーム側の {@code TeamService#assertSupporterEnabled} と対の実装（双子構成）。</p>
     *
     * @param orgId 組織内部 ID
     * @throws BusinessException 組織が存在しない（ORG_001）/ サポーター機能が無効
     */
    public void assertSupporterEnabled(Long orgId) {
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
        if (!Boolean.TRUE.equals(org.getSupporterEnabled())) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_SUPPORTER_DISABLED);
        }
    }

    /**
     * 組織名から一意スラッグを生成する。
     *
     * <p>ベーススラッグが既に使用中の場合は数値サフィックス (-1, -2, ...) を付与して一意化する。
     * 100 回試行しても一意にならない場合はタイムスタンプベースのサフィックスを使用する。</p>
     *
     * @param name 組織名
     * @return 一意なスラッグ
     */
    public String createUniqueSlug(String name) {
        String base = SlugGenerator.generate(name);
        if (!organizationRepository.existsBySlugAndDeletedAtIsNull(base)) {
            return base;
        }
        for (int i = 1; i <= 100; i++) {
            String candidate = SlugGenerator.withSuffix(base, i);
            if (!organizationRepository.existsBySlugAndDeletedAtIsNull(candidate)) {
                return candidate;
            }
        }
        return SlugGenerator.withSuffix(base, (int) (System.currentTimeMillis() % 10000));
    }

    /**
     * 作成時の slug を解決する（村方式に統一）。
     *
     * <p>ユーザーが slug を指定した場合は形式・予約語・一意性を検証して採用する。
     * 未指定（null / 空文字）の場合は従来どおり {@link #createUniqueSlug(String)} で
     * 組織名から自動生成（提案フォールバック）する。自動生成は降格扱いだが破壊的変更ではない。</p>
     *
     * @param requestedSlug ユーザー入力 slug（null / 空文字可）
     * @param name          組織名（自動生成フォールバック用）
     * @return 採用する一意な slug
     * @throws BusinessException 形式不正（ORG_060）/ 予約語（ORG_061）/ 重複（ORG_062）
     */
    private String resolveSlugForCreate(String requestedSlug, String name) {
        if (!SlugValidator.isProvided(requestedSlug)) {
            return createUniqueSlug(name);
        }
        validateUserSlug(requestedSlug);
        return requestedSlug;
    }

    /**
     * ユーザー指定 slug の形式・予約語・一意性を検証する。村の {@code validateSlug} と同方式。
     *
     * @param slug ユーザー入力 slug（指定済み前提）
     * @throws BusinessException 形式不正（ORG_060）/ 予約語（ORG_061）/ 重複（ORG_062）
     */
    private void validateUserSlug(String slug) {
        if (!SlugValidator.isValidFormat(slug)) {
            throw new BusinessException(OrgErrorCode.ORG_060);
        }
        if (SlugValidator.isReserved(slug)) {
            throw new BusinessException(OrgErrorCode.ORG_061);
        }
        if (organizationRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            throw new BusinessException(OrgErrorCode.ORG_062);
        }
        // F01.2 §5.9.5: 他組織の過去 slug（履歴予約）は恒久 301 を壊さないため使用不可。
        // 作成時は自組織という概念が無いので全履歴を対象に弾く。
        if (organizationSlugHistoryRepository.existsByOldSlug(slug)) {
            throw new BusinessException(OrgErrorCode.ORG_063);
        }
    }

    /**
     * slug の可用性をチェックする（作成前のリアルタイム検証 API 用）。
     *
     * <p>形式不正・予約語・重複のいずれかに該当すれば {@code available=false} と理由コードを返す。
     * 例外は投げず、常に 200 で結果を返す。</p>
     *
     * @param slug チェック対象 slug
     * @return 可用性結果（available と reason）
     */
    public SlugAvailabilityResponse checkSlugAvailability(String slug) {
        if (!SlugValidator.isProvided(slug)) {
            return new SlugAvailabilityResponse(false, "SLUG_REQUIRED");
        }
        if (!SlugValidator.isValidFormat(slug)) {
            return new SlugAvailabilityResponse(false, "SLUG_INVALID_FORMAT");
        }
        if (SlugValidator.isReserved(slug)) {
            return new SlugAvailabilityResponse(false, "SLUG_RESERVED");
        }
        if (organizationRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            return new SlugAvailabilityResponse(false, "SLUG_ALREADY_TAKEN");
        }
        // F01.2 §5.9.5: 他組織の過去 slug（履歴予約）は使用不可（恒久 301 保全）
        if (organizationSlugHistoryRepository.existsByOldSlug(slug)) {
            return new SlugAvailabilityResponse(false, "SLUG_RETIRED");
        }
        return new SlugAvailabilityResponse(true, null);
    }

    /**
     * F01.2 §5.9.5: 組織の slug を変更する（リネーム専用・既存 update とは分離）。
     *
     * <p>処理の流れ:</p>
     * <ol>
     *   <li>新 slug が現 slug と同一なら no-op（履歴も書かず現 slug を返す）。</li>
     *   <li>形式・予約語・一意性・他組織履歴予約を検証する（自組織の過去 slug への戻しは許可）。</li>
     *   <li>旧 slug を {@code organization_slug_history} に INSERT → org.slug を新 slug に更新（同一トランザクション）。</li>
     * </ol>
     *
     * <p>認可（ADMIN/DEPUTY 相当）は Controller で {@code AccessControlService.checkAdminOrAbove} が
     * 担保する前提（F00 正準）。本メソッドは認可済み呼び出しを前提とする。</p>
     *
     * @param orgId   対象組織 ID
     * @param newSlug 新しい slug
     * @return 更新後の組織レスポンス（no-op 時も現状を返す）
     * @throws BusinessException 形式不正（ORG_060）/ 予約語（ORG_061）/ 重複（ORG_062）/ 他組織履歴予約（ORG_063）
     */
    @Transactional
    @CacheEvict(value = "org-detail", allEntries = true)
    public ApiResponse<OrganizationResponse> renameSlug(Long orgId, String newSlug) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        String oldSlug = org.getSlug();

        // 同一 slug は no-op（200・履歴を増やさない）
        if (oldSlug.equals(newSlug)) {
            int memberCount = (int) userRoleRepository.countByOrganizationId(orgId);
            return ApiResponse.of(toResponse(org, memberCount));
        }

        validateRenameSlug(newSlug, orgId);

        // 旧 slug を履歴へ記録（恒久予約＋301 解決元）
        organizationSlugHistoryRepository.save(OrganizationSlugHistoryEntity.builder()
                .organizationId(orgId)
                .oldSlug(oldSlug)
                .build());

        org.renameSlug(newSlug);
        organizationRepository.save(org);

        log.info("組織 slug リネーム完了: orgId={}, {} -> {}", orgId, oldSlug, newSlug);

        int memberCount = (int) userRoleRepository.countByOrganizationId(orgId);
        return ApiResponse.of(toResponse(org, memberCount));
    }

    /**
     * リネーム時の新 slug を検証する。作成時の {@link #validateUserSlug(String)} と同方式だが、
     * 履歴予約チェックは「自組織を除外」する（自組織の過去 slug への戻しを許可するため）。
     *
     * @param slug  新 slug
     * @param orgId 自組織 ID（履歴予約判定から除外）
     */
    private void validateRenameSlug(String slug, Long orgId) {
        if (!SlugValidator.isValidFormat(slug)) {
            throw new BusinessException(OrgErrorCode.ORG_060);
        }
        if (SlugValidator.isReserved(slug)) {
            throw new BusinessException(OrgErrorCode.ORG_061);
        }
        if (organizationRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            throw new BusinessException(OrgErrorCode.ORG_062);
        }
        // 他組織の履歴に予約済みなら不可。自組織の過去 slug への戻しは許可（orgId 除外）。
        if (organizationSlugHistoryRepository.existsByOldSlugAndOrganizationIdNot(slug, orgId)) {
            throw new BusinessException(OrgErrorCode.ORG_063);
        }
    }

    /**
     * F01.2 §5.9.5: slug を解決する（旧 slug → 現 slug の 301 判定用・公開 EP から呼ばれる）。
     *
     * <ul>
     *   <li>現 slug で存在 → {@code CURRENT}</li>
     *   <li>無ければ履歴の old_slug 一致を引き、その組織の現 slug へ {@code MOVED(canonicalSlug)}。
     *       ただし対象組織が論理削除済み等で現存しない場合は {@code NOT_FOUND}。</li>
     *   <li>どちらも無ければ {@code NOT_FOUND}</li>
     * </ul>
     *
     * <p>スコープ漏洩対策: 名前等は返さず canonicalSlug のみ。private 組織の実データは
     * {@code getOrganization} の認可が守るため、slug→slug の対応自体は非機密として扱う。</p>
     *
     * @param slug 解決対象 slug
     * @return 解決結果
     */
    public SlugResolveResponse resolveSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return SlugResolveResponse.notFound();
        }
        if (organizationRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            return SlugResolveResponse.current();
        }
        return organizationSlugHistoryRepository.findByOldSlug(slug)
                .flatMap(history -> organizationRepository.findById(history.getOrganizationId()))
                .map(org -> SlugResolveResponse.moved(org.getSlug()))
                .orElseGet(SlugResolveResponse::notFound);
    }

    /**
     * 組織を更新する。
     */
    @Transactional
    @CacheEvict(value = "org-detail", allEntries = true)
    public ApiResponse<OrganizationResponse> updateOrganization(Long orgId, UpdateOrganizationRequest req) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        checkNotArchived(org);

        // 直接ミューテートで UPDATE を発行する（PR #1643 と同型）。
        // toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化し、
        // slug 一意制約違反で 500 になるため使わない。enum 解決は本層の責務。
        // 楽観ロック用バージョンチェックはJPAの@Versionで自動処理。
        OrganizationEntity.Visibility visibility = req.getVisibility() != null
                ? OrganizationEntity.Visibility.valueOf(req.getVisibility())
                : null;
        OrganizationEntity.HierarchyVisibility hierarchyVisibility = req.getHierarchyVisibility() != null
                ? OrganizationEntity.HierarchyVisibility.valueOf(req.getHierarchyVisibility())
                : null;
        org.applyUpdate(
                req.getName(),
                req.getNameKana(),
                req.getNickname1(),
                req.getNickname2(),
                req.getPrefecture(),
                req.getCity(),
                visibility,
                hierarchyVisibility,
                req.getSupporterEnabled());
        organizationRepository.save(org);

        int memberCount = (int) userRoleRepository.countByOrganizationId(orgId);
        log.info("組織更新完了: orgId={}", orgId);
        return ApiResponse.of(toResponse(org, memberCount));
    }

    /**
     * 組織を論理削除する。招待トークンも一括失効。
     *
     * <p>認可（当該組織の ADMIN/DEPUTY 相当）は Controller で
     * {@code AccessControlService.checkAdminOrAbove} が担保する（F00 正準）。
     * 本メソッドは認可済み呼び出しを前提とする。</p>
     */
    @Transactional
    // TODO: OrganizationドメインとRoleドメインをまたいでいる。将来はOrganizationDeletedEventで分離予定
    @CacheEvict(value = "org-detail", allEntries = true)
    public void deleteOrganization(Long orgId, Long userId) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        org.softDelete();

        // 招待トークン一括失効
        inviteTokenRepository.findByOrganizationIdAndRevokedAtIsNull(orgId)
                .forEach(InviteTokenEntity::revoke);

        // 監査ログ用イベント発行
        eventPublisher.publishEvent(new OrganizationDeletedEvent(userId, orgId));

        log.info("組織削除完了: orgId={}, userId={}", orgId, userId);
    }

    /**
     * 組織をアーカイブする。
     *
     * <p><b>認可は呼び出し元（public 入口）が担保する。本メソッドにガードを置いてはならない。</b>
     * 本メソッドは 2 つの入口から呼ばれ、要求される権限が入口ごとに異なるためである:</p>
     * <ul>
     *   <li>{@code OrganizationController#archiveOrganization} — 当該組織の ADMIN/DEPUTY
     *       （{@code checkAdminOrAbove}）</li>
     *   <li>{@code SystemAdminDashboardController#freezeOrganization} — SYSTEM_ADMIN
     *       （{@code /api/v1/system-admin/**} の SecurityConfig パスルール {@code hasRole("SYSTEM_ADMIN")}）</li>
     * </ul>
     * <p>ここに {@code checkAdminOrAbove} を置くと、対象組織のメンバーではない SYSTEM_ADMIN による
     * 管理コンソールからの凍結が巻き添えで 403 になる。</p>
     */
    @Transactional
    @CacheEvict(value = "org-detail", allEntries = true)
    public void archiveOrganization(Long orgId) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        if (org.getArchivedAt() != null) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
        org.archive();
        log.info("組織アーカイブ完了: orgId={}", orgId);
    }

    /**
     * 組織のアーカイブを解除する。
     *
     * <p><b>認可は呼び出し元（public 入口）が担保する。</b>理由は
     * {@link #archiveOrganization(Long)} と同じ（組織 ADMIN 経路と SYSTEM_ADMIN 経路の 2 入口を持つ）。</p>
     */
    @Transactional
    public void unarchiveOrganization(Long orgId) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        org.unarchive();
        log.info("組織アーカイブ解除完了: orgId={}", orgId);
    }

    /**
     * 組織をキーワード検索する。
     */
    public PagedResponse<OrganizationSummaryResponse> searchOrganizations(String keyword, Pageable pageable) {
        Page<OrganizationEntity> page = organizationRepository.searchByKeyword(
                keyword != null ? keyword : "", pageable);

        var data = page.getContent().stream()
                .map(org -> {
                    int memberCount = (int) userRoleRepository.countByOrganizationId(org.getId());
                    return new OrganizationSummaryResponse(
                            org.getSlug(), org.getSlug(), org.getName(), org.getOrgType().name(),
                            org.getVisibility().name(), memberCount);
                })
                .toList();

        var meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(data, meta);
    }

    /**
     * 組織のメンバー一覧を取得する。
     *
     * <p>{@link OrganizationMembershipService#getMembers(Long, Pageable)} へ委譲。</p>
     */
    public PagedResponse<MemberResponse> getMembers(Long orgId, Pageable pageable) {
        return organizationMembershipService.getMembers(orgId, pageable);
    }

    // ========================================
    // フォロー（SUPPORTER）
    // ========================================

    /**
     * 組織をフォロー（SUPPORTER として memberships に入会）する。
     *
     * <p>{@link OrganizationMembershipService#followOrganization(Long, Long)} へ委譲。</p>
     */
    public void followOrganization(Long userId, Long orgId) {
        organizationMembershipService.followOrganization(userId, orgId);
    }

    /**
     * 組織のフォローを解除する。
     *
     * <p>{@link OrganizationMembershipService#unfollowOrganization(Long, Long)} へ委譲。</p>
     */
    public void unfollowOrganization(Long userId, Long orgId) {
        organizationMembershipService.unfollowOrganization(userId, orgId);
    }

    /**
     * 組織に所属するチーム一覧を取得する（team_org_memberships.status = ACTIVE）。
     *
     * <p>{@link OrganizationMembershipService#getTeams(Long)} へ委譲。</p>
     */
    public List<OrgTeamSummaryResponse> getTeams(Long orgId) {
        return organizationMembershipService.getTeams(orgId);
    }

    /**
     * 組織配下の全メンバーを取得する。
     * scope: ORGANIZATION=直属のみ / TEAM=チームメンバーのみ / INDIVIDUAL=全員
     *
     * <p>{@link OrganizationMembershipService#getAllMembers(Long, String)} へ委譲。</p>
     */
    public List<OrgAllMembersResponse> getAllMembers(Long orgId, String scope) {
        return organizationMembershipService.getAllMembers(orgId, scope);
    }

    /**
     * 論理削除済み組織を復元する（SYSTEM_ADMIN専用）。
     *
     * <p>認可は Controller で {@code AccessControlService.checkSystemAdmin} が担保する。
     * 組織 ADMIN では不可（自組織を任意に復活させられてしまうため）。</p>
     *
     * <p><b>既知の制約（本メソッドは現状 本来の用途で到達不能）</b>:
     * 唯一の呼び出し元 {@code OrganizationController#restoreOrganization} は
     * {@code resolveOrgId(slug)} で slug を解決するが、その実体
     * {@code findBySlugAndDeletedAtIsNull} は論理削除済み組織を除外する。
     * したがって「削除済み組織を slug 指定で復元する」経路は成立せず、常に {@code ORG_001} になる。
     * 復元機能を実際に使うには、削除済みを含めて解決する経路（ID 指定 EP 等）が別途必要。
     * これは認可とは独立した既存の機能欠陥であり、修正は別タスクとする。</p>
     */
    @Transactional
    public void restoreOrganization(Long orgId) {
        if (organizationRepository.countByIdIncludingDeleted(orgId) == 0) {
            throw new BusinessException(OrgErrorCode.ORG_001);
        }
        int updated = organizationRepository.restoreById(orgId);
        if (updated == 0) {
            throw new BusinessException(OrgErrorCode.ORG_006);
        }
        log.info("組織復元完了: orgId={}", orgId);
    }

    // ========================================
    // F01.2 階層表示API
    // ========================================

    /**
     * 対象組織の祖先チェーン（root → 直近の親 の順）を返す。
     *
     * <p>{@link OrganizationHierarchyService#getAncestors(Long, Long)} へ委譲。</p>
     */
    public AncestorsResponse getAncestors(Long orgId, Long requesterId) {
        return organizationHierarchyService.getAncestors(orgId, requesterId);
    }

    /**
     * 対象組織の直近の子組織一覧を返す。
     *
     * <p>{@link OrganizationHierarchyService#getChildren(Long, Long, String, int)} へ委譲。</p>
     */
    public ChildrenResponse getChildren(Long orgId, Long requesterId, String cursor, int size) {
        return organizationHierarchyService.getChildren(orgId, requesterId, cursor, size);
    }

    // ========================================
    // ドメイン間 slug 解決（Todoドメイン等から利用）
    // ========================================

    /**
     * 指定 ID 集合に対して id → slug のマッピングを一括取得する（N+1 回避）。
     *
     * <p>TodoResponseConverter 等の他ドメインが organization Entity を直接参照することを防ぐために
     * プリミティブ（Map&lt;Long, String&gt;）のみを返す。Entity は漏らさない。</p>
     *
     * @param ids 取得対象の組織 ID 集合
     * @return id → slug の Map（論理削除済みは除外）。ids が空の場合は空 Map を返す
     */
    public Map<Long, String> getSlugsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return organizationRepository.findSlugMapByIdIn(ids);
    }

    /**
     * 指定 ID 集合に対して id → name（組織名）のマッピングを一括取得する（N+1 回避）。
     *
     * <p>マイページ 組織プロジェクト集約で {@code ProjectService} が組織名を付与する際に使用する。
     * プリミティブ（Map&lt;Long, String&gt;）のみを返し、Entity は漏らさない。
     * {@link #getSlugsByIds(Collection)} と対をなす。</p>
     *
     * @param ids 取得対象の組織 ID 集合
     * @return id → name の Map（論理削除済みは除外）。ids が空の場合は空 Map を返す
     */
    public Map<Long, String> getNamesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return organizationRepository.findNameMapByIdIn(ids);
    }

    /**
     * 単一組織 ID から slug を取得する。
     *
     * <p>論理削除済み・存在しない場合は {@code null} を返す（例外を投げない）。</p>
     *
     * @param id 組織 ID
     * @return slug 文字列。存在しない場合は null
     */
    public String getSlugById(Long id) {
        if (id == null) {
            return null;
        }
        return organizationRepository.findById(id).map(o -> o.getSlug()).orElse(null);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private OrganizationEntity findOrganizationOrThrow(Long orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
    }

    /**
     * slug で組織を取得する。存在しない場合は 404 例外を投げる（IDOR 対策）。
     *
     * @param slug URL 識別子（カスタムスラッグ）
     * @return 組織エンティティ
     */
    private OrganizationEntity findOrganizationBySlugOrThrow(String slug) {
        return organizationRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
    }

    private void checkNotArchived(OrganizationEntity org) {
        if (org.getArchivedAt() != null) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
    }

    private OrganizationResponse toResponse(OrganizationEntity org, int memberCount) {
        return OrganizationResponse.builder()
                .id(org.getSlug())
                .slug(org.getSlug())
                .numericId(org.getId())
                .basicInfo(new OrganizationResponse.OrgBasicInfoDto(
                        org.getName(), org.getNameKana(),
                        org.getNickname1(), org.getNickname2()))
                .hierarchy(new OrganizationResponse.OrgHierarchyDto(
                        org.getOrgType() != null ? org.getOrgType().name() : null,
                        org.getParentOrganizationId()))
                .location(new OrganizationResponse.OrgLocationDto(
                        org.getPrefecture(), org.getCity()))
                .visibility(new OrganizationResponse.OrgVisibilityDto(
                        org.getVisibility() != null ? org.getVisibility().name() : null,
                        org.getHierarchyVisibility() != null ? org.getHierarchyVisibility().name() : null,
                        org.getSupporterEnabled()))
                .metadata(new OrganizationResponse.OrgMetadataDto(
                        org.getVersion(), memberCount,
                        // 画像 URL 根治 Phase 2: 生 R2 キーを署名付き表示 URL（絶対 URL）へ解決して返す。
                        mediaUrlResolver.resolve(org.getIconUrl()),
                        mediaUrlResolver.resolve(org.getBannerUrl())))
                .timestamps(new OrganizationResponse.OrgTimestampsDto(
                        org.getArchivedAt(), org.getCreatedAt()))
                .build();
    }
}
