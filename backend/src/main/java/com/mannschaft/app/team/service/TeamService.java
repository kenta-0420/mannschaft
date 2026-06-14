package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.event.TeamCreatedEvent;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import com.mannschaft.app.team.event.TeamMemberRemovedEvent;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.common.util.SlugGenerator;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.dto.TeamOrgSummaryResponse;
import com.mannschaft.app.team.dto.TeamPublicDetailResponse;
import com.mannschaft.app.team.dto.TeamResponse;
import com.mannschaft.app.team.dto.TeamSummaryResponse;
import com.mannschaft.app.team.dto.UpdateTeamRequest;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チーム管理サービス。チームのCRUD・アーカイブ・メンバー一覧・SUPPORTER管理を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamBlockRepository teamBlockRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TeamFriendRepository teamFriendRepository;
    private final TeamShiftSettingsService teamShiftSettingsService;
    private final MeterRegistry meterRegistry;
    private final MemberQueryDispatcher memberQueryDispatcher;
    private final MembershipService membershipService;
    private final MembershipRepository membershipRepository;

    /**
     * チームを作成し、作成者をADMINロールで紐付ける。
     */
    @Transactional
    // TODO: teamドメインがroleドメイン(RoleRepository/UserRoleRepository)・socialドメイン(TeamFriendRepository)・membershipドメイン(MembershipRepository/MembershipService)・shiftドメイン(TeamShiftSettingsService)をまたいでいる。将来はTeamCreatedEventで分離予定
    @CacheEvict(value = "team-search", allEntries = true)
    public ApiResponse<TeamResponse> createTeam(Long userId, CreateTeamRequest req) {
        String slug = createUniqueSlug(req.getName());
        TeamEntity team = TeamEntity.builder()
                .name(req.getName())
                .slug(slug)
                .template(req.getTemplate())
                .prefecture(req.getPrefecture())
                .city(req.getCity())
                .visibility(req.getVisibility() != null
                        ? TeamEntity.Visibility.valueOf(req.getVisibility())
                        : TeamEntity.Visibility.GUESTS_AND_ABOVE)
                .supporterEnabled(false)
                .build();
        // F22.1 市 Phase 2 足場C: 構造化地域コードを反映（どちらも null 許容＝未指定はそのまま NULL）
        team.updateRegionCodes(req.getPrefectureCode(), req.getCityCode());
        teamRepository.save(team);

        // 作成者をADMINロールで紐付ける
        RoleEntity adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_005));
        UserRoleEntity userRole = UserRoleEntity.builder()
                .userId(userId)
                .roleId(adminRole.getId())
                .teamId(team.getId())
                .build();
        userRoleRepository.save(userRole);

        // F00.5 認可基盤根治: memberships にも MEMBER として入会させる。
        // 認可（AccessControlService.isMember）は memberships を真実の源とするため、
        // user_roles だけでは作成者本人が自チームから 403 で締め出される構造的欠陥を防ぐ。
        // 権限ロール（ADMIN）は user_roles が担い、membership は在籍有無のみ表す（role_kind=MEMBER）。
        MembershipCreateRequest membershipReq = new MembershipCreateRequest();
        membershipReq.setUserId(userId);
        membershipReq.setScopeType(ScopeType.TEAM);
        membershipReq.setScopeId(team.getId());
        membershipReq.setRoleKind(RoleKind.MEMBER);
        membershipReq.setSource("TEAM_CREATE");
        membershipService.join(membershipReq);

        // チームシフト設定をデフォルト値で初期化
        teamShiftSettingsService.initializeDefaultSettings(team.getId());

        // 監査ログ用イベント発行
        eventPublisher.publishEvent(new TeamCreatedEvent(userId, team.getId(), team.getName()));

        log.info("チーム作成完了: teamId={}, userId={}", team.getId(), userId);
        long teamFriendCount = teamFriendRepository.countFriendsByTeamId(team.getId());
        // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
        long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                ScopeType.TEAM, team.getId(), RoleKind.SUPPORTER);
        return ApiResponse.of(toResponse(team, 1, teamFriendCount, supporterCount));
    }

    /**
     * F15.4 Phase 5-α: 店舗詳細を <strong>未ログイン</strong> でも取得できる公開エンドポイント用メソッド。
     *
     * <p>設計書: {@code docs/features/F15.4_phase5_team_public_detail.md} §4
     *
     * <p>以下のいずれかに該当する場合は {@link BusinessException}（{@link TeamErrorCode#TEAM_001}
     * → 404 にマップ）を投げる。エニュメレーション対策で他の状態と区別せず一律 404 とする。
     * <ul>
     *   <li>チーム不在</li>
     *   <li>論理削除済み（{@code deletedAt != null}）— {@code @SQLRestriction} により
     *       通常クエリでは取得されないが、念のためチェック</li>
     *   <li>アーカイブ済み（{@code archivedAt != null}）— マスター裁可: 404</li>
     *   <li>{@code visibility != PUBLIC}</li>
     * </ul>
     *
     * <p>既存 {@link #getTeam(Long)} は<b>無傷</b>で残し、こちらは別途
     * {@link TeamPublicDetailResponse}（抑制版 DTO）を返す。
     */
    public TeamPublicDetailResponse getPublicTeam(Long teamId) {
        TeamEntity team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
        // 念のための二重チェック（@SQLRestriction で deletedAt IS NULL は通常担保される）
        if (team.getDeletedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_001);
        }
        // archived は未ログインアクセスに対しては 404（マスター裁可）
        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_001);
        }
        // 公開可視性以外は 404（IDOR / エニュメレーション対策）
        if (team.getVisibility() != TeamEntity.Visibility.PUBLIC) {
            throw new BusinessException(TeamErrorCode.TEAM_001);
        }
        return TeamPublicDetailResponse.from(team);
    }

    /**
     * F22.1 市 Phase 2 足場C: チームの構造化地域コード（都道府県・市区町村）を取得する。
     *
     * <p>他ドメイン（recruitment）が札立て地域の既定補完に使う read-only な横断クエリ。
     * クロスドメイン FK を張らない方針（CLAUDE.md 原則 1）のため、Entity を直接渡さず
     * {@link TeamRegionCodes}（コードのみの軽量 DTO）として公開する。</p>
     *
     * <p>論理削除済み（{@code @SQLRestriction}）チームは取得対象外（空を返す）。
     * 地域コードが未設定の場合は record のフィールドが {@code null} のまま返る。</p>
     *
     * @param teamId チーム ID
     * @return 地域コード。チームが存在しない／論理削除済みの場合は空。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<TeamRegionCodes> findRegionCodes(Long teamId) {
        return teamRepository.findById(teamId)
                .map(team -> new TeamRegionCodes(team.getPrefectureCode(), team.getCityCode()));
    }

    /**
     * チームの構造化地域コード（都道府県・市区町村）。
     *
     * @param prefectureCode 都道府県コード（JIS X 0401、null=未設定）
     * @param cityCode       市区町村コード（JIS X 0402、null=未設定）
     */
    public record TeamRegionCodes(String prefectureCode, String cityCode) {
    }

    /**
     * チームを slug（URL識別子）で取得する。
     *
     * <p>Phase 4-E: Valkey にて 10 分キャッシュ。更新・削除時に自動無効化される。</p>
     */
    @Cacheable(value = "team-detail", key = "#slug")
    public ApiResponse<TeamResponse> getTeam(String slug) {
        TeamEntity team = findTeamBySlugOrThrow(slug);
        Long teamId = team.getId();
        int memberCount = (int) userRoleRepository.countByTeamId(teamId);
        long teamFriendCount = teamFriendRepository.countFriendsByTeamId(teamId);
        // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
        long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        return ApiResponse.of(toResponse(team, memberCount, teamFriendCount, supporterCount));
    }

    /**
     * slug から内部 BIGINT ID を解決する（Controller から他の Service メソッドに渡す用）。
     *
     * @param slug URL 識別子（カスタムスラッグ）
     * @return 内部 BIGINT ID
     */
    public Long resolveTeamId(String slug) {
        return findTeamBySlugOrThrow(slug).getId();
    }

    /**
     * チーム名から一意スラッグを生成する。
     *
     * <p>ベーススラッグが既に使用中の場合は数値サフィックス (-1, -2, ...) を付与して一意化する。
     * 100 回試行しても一意にならない場合はタイムスタンプベースのサフィックスを使用する。</p>
     *
     * @param name チーム名
     * @return 一意なスラッグ
     */
    public String createUniqueSlug(String name) {
        String base = SlugGenerator.generate(name);
        if (!teamRepository.existsBySlugAndDeletedAtIsNull(base)) {
            return base;
        }
        for (int i = 1; i <= 100; i++) {
            String candidate = SlugGenerator.withSuffix(base, i);
            if (!teamRepository.existsBySlugAndDeletedAtIsNull(candidate)) {
                return candidate;
            }
        }
        return SlugGenerator.withSuffix(base, (int) (System.currentTimeMillis() % 10000));
    }

    /**
     * チームを更新する。
     */
    @Transactional
    // TODO: teamドメインがroleドメイン(UserRoleRepository)・socialドメイン(TeamFriendRepository)・membershipドメイン(MembershipRepository)をまたいでいる。将来はTeamUpdatedEventで分離予定
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true)
    })
    public ApiResponse<TeamResponse> updateTeam(Long teamId, UpdateTeamRequest req) {
        TeamEntity team = findTeamOrThrow(teamId);
        checkNotArchived(team);

        TeamEntity updated = team.toBuilder()
                .name(req.getName() != null ? req.getName() : team.getName())
                .nameKana(req.getNameKana() != null ? req.getNameKana() : team.getNameKana())
                .nickname1(req.getNickname1() != null ? req.getNickname1() : team.getNickname1())
                .nickname2(req.getNickname2() != null ? req.getNickname2() : team.getNickname2())
                .template(req.getTemplate() != null ? req.getTemplate() : team.getTemplate())
                .prefecture(req.getPrefecture() != null ? req.getPrefecture() : team.getPrefecture())
                .city(req.getCity() != null ? req.getCity() : team.getCity())
                // F22.1 市 Phase 2 足場C: 地域コードは指定時のみ更新（null は既存値を維持）
                .prefectureCode(req.getPrefectureCode() != null ? req.getPrefectureCode() : team.getPrefectureCode())
                .cityCode(req.getCityCode() != null ? req.getCityCode() : team.getCityCode())
                .visibility(req.getVisibility() != null
                        ? TeamEntity.Visibility.valueOf(req.getVisibility())
                        : team.getVisibility())
                .supporterEnabled(req.getSupporterEnabled() != null ? req.getSupporterEnabled() : team.getSupporterEnabled())
                // F15.4 Phase 5-β: Google Maps 埋め込み URL。null 許容（地図なしも OK）
                .mapEmbedUrl(req.getMapEmbedUrl() != null ? req.getMapEmbedUrl() : team.getMapEmbedUrl())
                .build();
        teamRepository.save(updated);

        int memberCount = (int) userRoleRepository.countByTeamId(teamId);
        long teamFriendCount = teamFriendRepository.countFriendsByTeamId(teamId);
        // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
        long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        log.info("チーム更新完了: teamId={}", teamId);
        return ApiResponse.of(toResponse(updated, memberCount, teamFriendCount, supporterCount));
    }

    /**
     * チームを論理削除する。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true)
    })
    public void deleteTeam(Long teamId, Long userId) {
        TeamEntity team = findTeamOrThrow(teamId);
        team.softDelete();

        // 監査ログ用イベント発行
        eventPublisher.publishEvent(new TeamDeletedEvent(userId, teamId));

        log.info("チーム削除完了: teamId={}, userId={}", teamId, userId);
    }

    /**
     * チームをアーカイブする。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true)
    })
    public void archiveTeam(Long teamId) {
        TeamEntity team = findTeamOrThrow(teamId);
        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_002);
        }
        team.archive();
        log.info("チームアーカイブ完了: teamId={}", teamId);
    }

    /**
     * チームのアーカイブを解除する。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true)
    })
    public void unarchiveTeam(Long teamId) {
        TeamEntity team = findTeamOrThrow(teamId);
        team.unarchive();
        log.info("チームアーカイブ解除完了: teamId={}", teamId);
    }

    /**
     * チームをキーワード検索する。
     */
    public PagedResponse<TeamSummaryResponse> searchTeams(String keyword, Pageable pageable) {
        Page<TeamEntity> page = teamRepository.searchByKeyword(
                keyword != null ? keyword : "", pageable);

        var data = page.getContent().stream()
                .map(team -> {
                    int memberCount = (int) userRoleRepository.countByTeamId(team.getId());
                    long teamFriendCount = teamFriendRepository.countFriendsByTeamId(team.getId());
                    // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
                    long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                            ScopeType.TEAM, team.getId(), RoleKind.SUPPORTER);
                    return new TeamSummaryResponse(
                            team.getSlug(), team.getSlug(), team.getName(), team.getTemplate(),
                            team.getVisibility().name(), memberCount,
                            teamFriendCount, supporterCount);
                })
                .toList();

        var meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(data, meta);
    }

    /**
     * チームのメンバー一覧を取得する。
     *
     * <p>F00.5 Phase 3: MemberQueryDispatcher 経由で memberships + user_roles を統合参照する。
     * Phase 2 の Shadow Mode（user_roles のみ参照）は廃止。</p>
     *
     * <p>設計書: docs/features/F00.5_membership_basis.md §7 / §13.6.4</p>
     */
    public PagedResponse<MemberResponse> getMembers(Long teamId, Pageable pageable) {
        findTeamOrThrow(teamId);

        // F00.5 Phase 3: MemberQueryDispatcher 経由で memberships 参照に完全切替
        var memberDtos = memberQueryDispatcher.queryMembers(teamId, ScopeType.TEAM, null);

        var data = memberDtos.stream()
                .map(dto -> new MemberResponse(
                        dto.userId(),
                        dto.displayName(),
                        dto.avatarUrl(),
                        dto.roleName(),
                        dto.joinedAt()))
                .toList();

        // Dispatcher は全件リストを返すため、ページネーションはアプリ側でエミュレート
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int size = pageable.isPaged() ? pageable.getPageSize() : data.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, data.size());
        List<MemberResponse> pagedData = (fromIndex >= data.size())
                ? List.<MemberResponse>of() : data.subList(fromIndex, toIndex);

        long totalElements = data.size();
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) totalElements / size);

        var meta = new PagedResponse.PageMeta(totalElements, page, size, totalPages);
        return PagedResponse.of(pagedData, meta);
    }

    /**
     * SUPPORTERとしてチームをフォローする（自己登録）。
     *
     * <p>F00.5 Phase 5: memberships への書き込みに切替。MembershipService.join() 経由で
     * 冪等性保証・イベント発火を一本化する。</p>
     */
    @Transactional
    // TODO: teamドメインとmembershipドメイン(MembershipRepository/MembershipService)をまたいでいる。将来はTeamFollowRequestedEventで分離予定
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true)
    })
    public void followTeam(Long userId, Long teamId) {
        findTeamOrThrow(teamId);

        // ブロックチェック
        if (teamBlockRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new BusinessException(TeamErrorCode.TEAM_004);
        }

        // 重複チェック（memberships に既にアクティブな SUPPORTER がいる場合）
        if (membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                userId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER)) {
            throw new BusinessException(TeamErrorCode.TEAM_003);
        }

        // F00.5 Phase 5: memberships に SUPPORTER として入会
        MembershipCreateRequest req = new MembershipCreateRequest();
        req.setUserId(userId);
        req.setScopeType(ScopeType.TEAM);
        req.setScopeId(teamId);
        req.setRoleKind(RoleKind.SUPPORTER);
        req.setSource("SELF_FOLLOW");
        membershipService.join(req);

        log.info("チームフォロー完了: userId={}, teamId={}", userId, teamId);
    }

    /**
     * SUPPORTERとしてのフォローを解除する。
     *
     * <p>F00.5 Phase 5: memberships への退会処理に切替。MembershipService.leave() 経由で
     * 退会履歴・イベント発火を一本化する。</p>
     */
    @Transactional
    // TODO: teamドメインとmembershipドメイン(MembershipRepository/MembershipService)をまたいでいる。将来はTeamUnfollowedEventで分離予定
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true)
    })
    public void unfollowTeam(Long userId, Long teamId) {
        findTeamOrThrow(teamId);

        // F00.5 Phase 5: memberships から SUPPORTER として退会
        Optional<MembershipEntity> active = membershipRepository.findActiveByUserAndScope(
                userId, ScopeType.TEAM, teamId);
        if (active.isPresent()) {
            MembershipLeaveRequest leaveReq = new MembershipLeaveRequest();
            leaveReq.setLeaveReason(LeaveReason.SELF);
            membershipService.leave(active.get().getId(), leaveReq);
        }

        // Phase 3: チームメンバー脱退イベント発行（行動メモのデフォルト投稿先リセット用）
        eventPublisher.publishEvent(new TeamMemberRemovedEvent(userId, teamId));
        log.info("チームフォロー解除完了: userId={}, teamId={}", userId, teamId);
    }

    /**
     * チームが所属する組織一覧を取得する。
     */
    public List<TeamOrgSummaryResponse> getOrganizations(Long teamId) {
        findTeamOrThrow(teamId);
        return teamOrgMembershipRepository.findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE)
                .stream()
                .map(m -> organizationRepository.findById(m.getOrganizationId()).orElse(null))
                .filter(org -> org != null)
                .map(org -> new TeamOrgSummaryResponse(
                        org.getSlug(),
                        org.getSlug(),
                        org.getName(),
                        null,
                        org.getVisibility().name(),
                        (int) userRoleRepository.countByOrganizationId(org.getId())))
                .toList();
    }

    /**
     * 論理削除済みチームを復元する（SYSTEM_ADMIN専用）。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true)
    })
    public void restoreTeam(Long teamId) {
        if (teamRepository.countByIdIncludingDeleted(teamId) == 0) {
            throw new BusinessException(TeamErrorCode.TEAM_001);
        }
        int updated = teamRepository.restoreById(teamId);
        if (updated == 0) {
            throw new BusinessException(TeamErrorCode.TEAM_006);
        }
        log.info("チーム復元完了: teamId={}", teamId);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private TeamEntity findTeamOrThrow(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
    }

    /**
     * slug でチームを取得する。存在しない場合は 404 例外を投げる（IDOR 対策）。
     *
     * @param slug URL 識別子（カスタムスラッグ）
     * @return チームエンティティ
     */
    private TeamEntity findTeamBySlugOrThrow(String slug) {
        return teamRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
    }

    private void checkNotArchived(TeamEntity team) {
        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_002);
        }
    }

    private TeamResponse toResponse(TeamEntity team, int memberCount,
                                     long teamFriendCount, long supporterCount) {
        return TeamResponse.builder()
                .id(team.getSlug())
                .slug(team.getSlug())
                .basicInfo(new TeamResponse.TeamBasicInfoDto(
                        team.getName(), team.getNameKana(),
                        team.getNickname1(), team.getNickname2()))
                .location(new TeamResponse.TeamLocationDto(
                        team.getPrefecture(), team.getCity(), team.getTemplate(),
                        team.getPrefectureCode(), team.getCityCode()))
                .visibility(new TeamResponse.TeamVisibilityDto(
                        team.getVisibility() != null ? team.getVisibility().name() : null,
                        team.getSupporterEnabled()))
                .metadata(new TeamResponse.TeamMetadataDto(
                        team.getVersion(), memberCount,
                        team.getIconUrl(), team.getBannerUrl(), team.getMapEmbedUrl()))
                .social(new TeamResponse.TeamSocialDto(teamFriendCount, supporterCount))
                .timestamps(new TeamResponse.TeamTimestampsDto(
                        team.getArchivedAt(), team.getCreatedAt()))
                .build();
    }
}
