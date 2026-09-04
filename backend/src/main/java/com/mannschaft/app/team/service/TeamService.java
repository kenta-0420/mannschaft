package com.mannschaft.app.team.service;

import com.mannschaft.app.common.duplicatename.DuplicateNameCandidate;
import com.mannschaft.app.common.duplicatename.DuplicateNameGuardService;
import com.mannschaft.app.common.duplicatename.DuplicateNameScopeKind;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.event.TeamCreatedEvent;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import com.mannschaft.app.team.event.TeamMemberRemovedEvent;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.repository.TeamSlugHistoryRepository;
import com.mannschaft.app.team.entity.TeamSlugHistoryEntity;
import com.mannschaft.app.common.dto.SlugAvailabilityResponse;
import com.mannschaft.app.common.dto.SlugResolveResponse;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.service.ScopeMemberCalendarSettingService;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.AdminRoleMutationLockService;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.common.util.SlugGenerator;
import com.mannschaft.app.common.util.SlugValidator;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private final TeamSlugHistoryRepository teamSlugHistoryRepository;
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
    private final ScopeMemberCalendarSettingService scopeMemberCalendarSettingService;
    private final MembershipService membershipService;
    private final MembershipRepository membershipRepository;
    /** 画像 URL 根治 Phase 1: 生 R2 キー → 署名付き表示 URL の解決を担う共通部品。 */
    private final MediaUrlResolver mediaUrlResolver;
    private final AdminRoleMutationLockService adminRoleMutationLockService;
    private final DuplicateNameGuardService duplicateNameGuardService;

    /**
     * チームを作成し、作成者をADMINロールで紐付ける。
     */
    @Transactional
    // TODO: teamドメインがroleドメイン(RoleRepository/UserRoleRepository)・socialドメイン(TeamFriendRepository)・membershipドメイン(MembershipRepository/MembershipService)・shiftドメイン(TeamShiftSettingsService)をまたいでいる。将来はTeamCreatedEventで分離予定
    @CacheEvict(value = "team-search", allEntries = true)
    public ApiResponse<TeamResponse> createTeam(Long userId, CreateTeamRequest req) {
        // CMP-260901-1538 柱③-A: チーム名の重複も組織と同様、409（候補一覧＋fingerprint）で
        // 確認を求める二段方式とする（従来チーム側には重複チェック自体が存在しなかった）。
        // 検分 P1-2 是正: 「候補再計算 → 作成」の全体をアドバイザリロック保持中に実行する
        // （TOCTOU 対策の設計判断は DuplicateNameGuardService の Javadoc を参照）。候補供給
        // コールバックはロッキングリード（FOR UPDATE）で最新のコミット済みデータを読む。
        return duplicateNameGuardService.checkForCreateAndRun(
                DuplicateNameScopeKind.TEAM,
                req.getName(),
                userId,
                req.isConfirmDuplicate(),
                req.getDuplicateNameFingerprint(),
                () -> teamRepository.findActiveByNormalizedNameForUpdate(req.getName()).stream()
                        .map(this::toDuplicateNameCandidate)
                        .toList(),
                () -> {
                    String slug = resolveSlugForCreate(req.getSlug(), req.getName());
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
                    Long adminRoleId = adminRoleMutationLockService.lockAdminRoleIdForCreation(userId)
                            .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_005));
                    teamRepository.save(team);

                    // 作成者をADMINロールで紐付ける
                    UserRoleEntity userRole = UserRoleEntity.builder()
                            .userId(userId)
                            .roleId(adminRoleId)
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
                });
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
        // 柱②-3 販促プロビジョニングゲート: PROVISIONED（承諾前の事前作成状態）は
        // 招待未承諾のため、他の非公開状態と同じく 404 に畳む（エニュメレーション対策）。
        if (team.isProvisioned()) {
            throw new BusinessException(TeamErrorCode.TEAM_001);
        }
        // 画像 URL 根治 Phase 1: icon/banner を署名付き表示 URL へ解決して渡す。
        return TeamPublicDetailResponse.from(
                team,
                mediaUrlResolver.resolve(team.getIconUrl()),
                mediaUrlResolver.resolve(team.getBannerUrl()));
    }

    /**
     * F06.4 公開活動記録: 他ドメインが「このチームは匿名公開してよいか」を判定するための横断 SPI。
     *
     * <p>公開コンテンツ（活動記録など）を匿名公開する経路は、コンテンツ自身が PUBLIC でも
     * <b>親スコープが非公開・凍結・停止なら 404 にしなければならない</b>
     * （親を見ないと「非公開チームの中身が PUBLIC 設定のまま漏れる」）。
     * 判定条件は {@link TeamRepository#findPublicTeamById(Long)} と同一の正準
     * （{@code visibility=PUBLIC} かつ {@code archivedAt IS NULL}、
     * {@code @SQLRestriction} により {@code deletedAt IS NULL}）。</p>
     *
     * <p>クロスドメイン Entity 参照を持ち込まないため（CLAUDE.md ドメイン境界の原則・番人 D-1）、
     * {@link TeamEntity} ではなく<b>チーム名のみ</b>を返す。呼び出し側はこれを
     * {@code PublicScopeRef}（公開用スコープ参照 DTO）に詰め替えて使う。</p>
     *
     * @param teamId 対象チーム ID
     * @return 公開してよいチームの表示名。非公開 / 凍結 / 削除済み / 不在なら空
     */
    public Optional<String> findPublicTeamNameById(Long teamId) {
        if (teamId == null) {
            return Optional.empty();
        }
        return teamRepository.findPublicTeamById(teamId).map(TeamEntity::getName);
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
     * チームの存在確認・アーカイブ状態・表示名を軽量サマリとして返す。
     *
     * <p>他ドメイン（role の承諾型招待 F04.12 等）が「スコープ存在確認・アーカイブ判定・
     * スコープ名解決」に使う read-only な横断クエリ。クロスドメインで Entity を直接渡さない方針
     * （CLAUDE.md 原則 1・原則 5）のため、{@link TeamSummary}（必要フィールドのみの軽量 DTO）
     * として公開する。</p>
     *
     * <p>論理削除済み（{@code @SQLRestriction}）チームは取得対象外（空を返す＝存在しない扱い）。</p>
     *
     * @param teamId チーム ID
     * @return チームサマリ。存在しない／論理削除済みの場合は空。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<TeamSummary> findTeamSummary(Long teamId) {
        return teamRepository.findById(teamId)
                .map(team -> new TeamSummary(
                        team.getName(), team.getArchivedAt() != null));
    }

    /**
     * チームの軽量サマリ（他ドメイン公開用）。
     *
     * @param name     チーム表示名
     * @param archived アーカイブ済みか（{@code archived_at} が非 NULL）
     */
    public record TeamSummary(String name, boolean archived) {
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
     * チームがサポーター受け入れを有効化していることを表明する。
     *
     * <p>{@code supporter_enabled} は「このチームがサポーター登録を受け付けるか」を表す
     * 運営者の意思表示であり、フロントエンドも本フラグでフォローボタンの表示を切り替えている
     * （{@code TeamPageHeader.vue}）。サーバ側でも同じ契約を強制し、無効化中のチームへの
     * サポーター自己登録を {@code MEMBERSHIP_SUPPORTER_DISABLED}（403）で拒否する。</p>
     *
     * @param teamId チーム内部 ID
     * @throws BusinessException チームが存在しない（TEAM_001）/ サポーター機能が無効
     */
    public void assertSupporterEnabled(Long teamId) {
        TeamEntity team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
        if (!Boolean.TRUE.equals(team.getSupporterEnabled())) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_SUPPORTER_DISABLED);
        }
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
     * 柱②-2 販促プロビジョニング専用: チームを {@code PROVISIONED} 状態で事前作成する。
     *
     * <p>D-1/D-5（クロスドメイン Entity/Repository 参照禁止）に従い、{@code provisioning}
     * ドメインへ {@link TeamEntity}/{@link TeamRepository} を漏らさず、この窓口経由で
     * 作成する。作成直後は ADMIN/membership を一切持たない（招待承諾で初めて付与される）。</p>
     *
     * <p>CMP-260901-1538 柱③-A: 通常作成（{@link #createTeam}）と同じ同名確認フローを通す。
     * PROVISIONED は常に MEMBERS_AND_ABOVE（非PUBLIC）のため候補は「存在のみ」開示となる。</p>
     *
     * @param name                     チーム名
     * @param slug                     一意 slug（{@link #createUniqueSlug} 等で事前採番済みのもの）
     * @param actorUserId              作成操作者（SYSTEM_ADMIN）のユーザーID。fingerprint 束縛に使う
     * @param confirmDuplicate         同名候補の存在を確認済みとして作成を続行するか
     * @param duplicateNameFingerprint {@code confirmDuplicate=true} 時に返送する fingerprint
     * @return 作成したチームの ID
     */
    @Transactional
    public Long createProvisionedTeam(String name, String slug, Long actorUserId,
            boolean confirmDuplicate, String duplicateNameFingerprint) {
        return duplicateNameGuardService.checkForCreateAndRun(
                DuplicateNameScopeKind.TEAM,
                name,
                actorUserId,
                confirmDuplicate,
                duplicateNameFingerprint,
                () -> teamRepository.findActiveByNormalizedNameForUpdate(name).stream()
                        .map(this::toDuplicateNameCandidate)
                        .toList(),
                () -> {
                    TeamEntity team = TeamEntity.builder()
                            .name(name)
                            .slug(slug)
                            // Team.Visibility に PRIVATE 相当は無いため、既存4値のうち最も制限的な
                            // MEMBERS_AND_ABOVE を採用する（承諾までメンバーが存在しないため実質非公開）。
                            .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                            .supporterEnabled(false)
                            .lifecycleStatus(TeamEntity.LifecycleStatus.PROVISIONED)
                            .build();
                    teamRepository.save(team);
                    return team.getId();
                });
    }

    /**
     * 柱②-2/②-3 販促プロビジョニング専用: 指定チームが {@code PROVISIONED}（承諾前）かどうかを返す。
     * 存在しないチームは非プロビジョニング（false）扱いとする（呼び出し元が別途404等を判断する）。
     */
    public boolean isProvisioned(Long teamId) {
        return teamRepository.findById(teamId).map(TeamEntity::isProvisioned).orElse(false);
    }

    /**
     * 柱②-2 販促プロビジョニング専用: 指定チームの {@code lifecycle_status} を
     * {@code PROVISIONED} から {@code ACTIVE} へ遷移させ、チーム名を返す。
     * 存在しなければ empty。
     */
    @Transactional
    public Optional<String> activateProvisionedTeam(Long teamId) {
        return teamRepository.findById(teamId).map(team -> {
            team.activate();
            teamRepository.save(team);
            return team.getName();
        });
    }

    /**
     * 柱②-2 販促プロビジョニング専用: チーム ID からチーム名を解決する（存在しなければ empty）。
     * 招待の表示名解決（下見/一覧/再送/取消）専用の軽量参照。
     */
    public Optional<String> findNameById(Long teamId) {
        return teamRepository.findById(teamId).map(TeamEntity::getName);
    }

    /**
     * 作成時の slug を解決する（村方式に統一）。
     *
     * <p>ユーザーが slug を指定した場合は形式・予約語・一意性を検証して採用する。
     * 未指定（null / 空文字）の場合は従来どおり {@link #createUniqueSlug(String)} で
     * チーム名から自動生成（提案フォールバック）する。自動生成は降格扱いだが破壊的変更ではない。</p>
     *
     * @param requestedSlug ユーザー入力 slug（null / 空文字可）
     * @param name          チーム名（自動生成フォールバック用）
     * @return 採用する一意な slug
     * @throws BusinessException 形式不正（TEAM_060）/ 予約語（TEAM_061）/ 重複（TEAM_062）
     */
    private String resolveSlugForCreate(String requestedSlug, String name) {
        if (!SlugValidator.isProvided(requestedSlug)) {
            return createUniqueSlug(name);
        }
        validateUserSlug(requestedSlug);
        return requestedSlug;
    }

    /**
     * CMP-260901-1538 柱③-A: 同名候補（{@link TeamEntity}）を確認要求 DTO へ変換する。
     * チームの可視性ラダーは PUBLIC/GUESTS_AND_ABOVE/SUPPORTERS_AND_ABOVE/MEMBERS_AND_ABOVE の
     * 4段階（組織の PUBLIC/PRIVATE 2値とは異なる）。最も安全側の方針として、PUBLIC のみ名称を
     * 開示し、それ以外は「存在のみ」を示す（金型: {@code OrganizationService#toDuplicateNameCandidate}）。
     */
    private DuplicateNameCandidate toDuplicateNameCandidate(TeamEntity candidate) {
        boolean nameVisible = candidate.getVisibility() == TeamEntity.Visibility.PUBLIC;
        return new DuplicateNameCandidate(
                String.valueOf(candidate.getId()),
                nameVisible,
                nameVisible ? candidate.getName() : null);
    }

    /**
     * ユーザー指定 slug の形式・予約語・一意性を検証する。村の {@code validateSlug} と同方式。
     *
     * @param slug ユーザー入力 slug（指定済み前提）
     * @throws BusinessException 形式不正（TEAM_060）/ 予約語（TEAM_061）/ 重複（TEAM_062）
     */
    private void validateUserSlug(String slug) {
        if (!SlugValidator.isValidFormat(slug)) {
            throw new BusinessException(TeamErrorCode.TEAM_060);
        }
        if (SlugValidator.isReserved(slug)) {
            throw new BusinessException(TeamErrorCode.TEAM_061);
        }
        if (teamRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            throw new BusinessException(TeamErrorCode.TEAM_062);
        }
        // F01.2 §5.9.5: 他チームの過去 slug（履歴予約）は恒久 301 を壊さないため使用不可。
        // 作成時は自チームという概念が無いので全履歴を対象に弾く。
        if (teamSlugHistoryRepository.existsByOldSlug(slug)) {
            throw new BusinessException(TeamErrorCode.TEAM_063);
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
        if (teamRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            return new SlugAvailabilityResponse(false, "SLUG_ALREADY_TAKEN");
        }
        // F01.2 §5.9.5: 他チームの過去 slug（履歴予約）は使用不可（恒久 301 保全）
        if (teamSlugHistoryRepository.existsByOldSlug(slug)) {
            return new SlugAvailabilityResponse(false, "SLUG_RETIRED");
        }
        return new SlugAvailabilityResponse(true, null);
    }

    /**
     * F01.2 §5.9.5: チームの slug を変更する（リネーム専用・既存 update とは分離）。
     *
     * <p>処理の流れ:</p>
     * <ol>
     *   <li>新 slug が現 slug と同一なら no-op（履歴も書かず現 slug を返す）。</li>
     *   <li>形式・予約語・一意性・他チーム履歴予約を検証する（自チームの過去 slug への戻しは許可）。</li>
     *   <li>旧 slug を {@code team_slug_history} に INSERT → team.slug を新 slug に更新（同一トランザクション）。</li>
     * </ol>
     *
     * <p>認可（ADMIN/DEPUTY 相当）は Controller で {@code AccessControlService.checkAdminOrAbove} が
     * 担保する前提（F00 正準）。本メソッドは認可済み呼び出しを前提とする。</p>
     *
     * @param teamId  対象チーム ID
     * @param newSlug 新しい slug
     * @return 更新後のチームレスポンス（no-op 時も現状を返す）
     * @throws BusinessException 形式不正（TEAM_060）/ 予約語（TEAM_061）/ 重複（TEAM_062）/ 他チーム履歴予約（TEAM_063）
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true)
    })
    public ApiResponse<TeamResponse> renameSlug(Long teamId, String newSlug) {
        TeamEntity team = findTeamOrThrow(teamId);
        String oldSlug = team.getSlug();

        // 同一 slug は no-op（200・履歴を増やさない）
        if (oldSlug.equals(newSlug)) {
            int memberCount = (int) userRoleRepository.countByTeamId(teamId);
            long teamFriendCount = teamFriendRepository.countFriendsByTeamId(teamId);
            long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                    ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
            return ApiResponse.of(toResponse(team, memberCount, teamFriendCount, supporterCount));
        }

        validateRenameSlug(newSlug, teamId);

        // 旧 slug を履歴へ記録（恒久予約＋301 解決元）
        teamSlugHistoryRepository.save(TeamSlugHistoryEntity.builder()
                .teamId(teamId)
                .oldSlug(oldSlug)
                .build());

        team.renameSlug(newSlug);
        teamRepository.save(team);

        log.info("チーム slug リネーム完了: teamId={}, {} -> {}", teamId, oldSlug, newSlug);

        int memberCount = (int) userRoleRepository.countByTeamId(teamId);
        long teamFriendCount = teamFriendRepository.countFriendsByTeamId(teamId);
        long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        return ApiResponse.of(toResponse(team, memberCount, teamFriendCount, supporterCount));
    }

    /**
     * リネーム時の新 slug を検証する。作成時の {@link #validateUserSlug(String)} と同方式だが、
     * 履歴予約チェックは「自チームを除外」する（自チームの過去 slug への戻しを許可するため）。
     *
     * @param slug   新 slug
     * @param teamId 自チーム ID（履歴予約判定から除外）
     */
    private void validateRenameSlug(String slug, Long teamId) {
        if (!SlugValidator.isValidFormat(slug)) {
            throw new BusinessException(TeamErrorCode.TEAM_060);
        }
        if (SlugValidator.isReserved(slug)) {
            throw new BusinessException(TeamErrorCode.TEAM_061);
        }
        if (teamRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            throw new BusinessException(TeamErrorCode.TEAM_062);
        }
        // 他チームの履歴に予約済みなら不可。自チームの過去 slug への戻しは許可（teamId 除外）。
        if (teamSlugHistoryRepository.existsByOldSlugAndTeamIdNot(slug, teamId)) {
            throw new BusinessException(TeamErrorCode.TEAM_063);
        }
    }

    /**
     * F01.2 §5.9.5: slug を解決する（旧 slug → 現 slug の 301 判定用・公開 EP から呼ばれる）。
     *
     * <ul>
     *   <li>現 slug で存在 → {@code CURRENT}</li>
     *   <li>無ければ履歴の old_slug 一致を引き、その team の現 slug へ {@code MOVED(canonicalSlug)}。
     *       ただし対象チームが論理削除済み等で現存しない場合は {@code NOT_FOUND}。</li>
     *   <li>どちらも無ければ {@code NOT_FOUND}</li>
     * </ul>
     *
     * <p>スコープ漏洩対策: 名前等は返さず canonicalSlug のみ。private チームの実データは
     * {@code getTeam} の認可が守るため、slug→slug の対応自体は非機密として扱う。</p>
     *
     * @param slug 解決対象 slug
     * @return 解決結果
     */
    public SlugResolveResponse resolveSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return SlugResolveResponse.notFound();
        }
        if (teamRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            return SlugResolveResponse.current();
        }
        return teamSlugHistoryRepository.findByOldSlug(slug)
                .flatMap(history -> teamRepository.findById(history.getTeamId()))
                .map(team -> SlugResolveResponse.moved(team.getSlug()))
                .orElseGet(SlugResolveResponse::notFound);
    }

    /**
     * チームを更新する。
     */
    @Transactional
    // TODO: teamドメインがroleドメイン(UserRoleRepository)・socialドメイン(TeamFriendRepository)・membershipドメイン(MembershipRepository)をまたいでいる。将来はTeamUpdatedEventで分離予定
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true),
            // issue #2496: フレンド一覧キャッシュ（teamFriendList）は相手チーム名
            // （TeamFriendView#friendTeamName）を内包するため、チーム名の変更で stale になる。
            // teamFriendList が実際に発火するようになった今、ここでの失効が必須。
            @CacheEvict(value = "teamFriendList", allEntries = true)
    })
    public ApiResponse<TeamResponse> updateTeam(Long teamId, UpdateTeamRequest req) {
        TeamEntity team = findTeamOrThrow(teamId);
        checkNotArchived(team);

        // 直接ミューテートで UPDATE を発行する（PR #1643 と同型）。
        // toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化し、
        // slug 一意制約違反で 500 になるため使わない。visibility の enum 解決は本層の責務。
        TeamEntity.Visibility visibility = req.getVisibility() != null
                ? TeamEntity.Visibility.valueOf(req.getVisibility())
                : null;
        team.applyUpdate(
                req.getName(),
                req.getNameKana(),
                req.getNickname1(),
                req.getNickname2(),
                req.getTemplate(),
                req.getPrefecture(),
                req.getCity(),
                // F22.1 市 Phase 2 足場C: 地域コードは指定時のみ更新（null は既存値を維持）
                req.getPrefectureCode(),
                req.getCityCode(),
                visibility,
                req.getSupporterEnabled(),
                // F15.4 Phase 5-β: Google Maps 埋め込み URL。指定時のみ更新（null は既存値を維持）
                req.getMapEmbedUrl());
        team.updateTimezone(req.getTimezone());
        teamRepository.save(team);

        int memberCount = (int) userRoleRepository.countByTeamId(teamId);
        long teamFriendCount = teamFriendRepository.countFriendsByTeamId(teamId);
        // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
        long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        log.info("チーム更新完了: teamId={}", teamId);
        return ApiResponse.of(toResponse(team, memberCount, teamFriendCount, supporterCount));
    }

    /**
     * チームを論理削除する。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "team-detail", allEntries = true),
            @CacheEvict(value = "team-search", allEntries = true),
            // issue #2496: 削除されたチームがフレンド一覧のキャッシュに残り続けないよう失効させる。
            @CacheEvict(value = "teamFriendList", allEntries = true)
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
     *
     * <p>認可根治 Wave6: {@code TeamRepository#searchByKeyword} が
     * <b>PUBLIC かつ未アーカイブ</b>に絞り込む。本メソッド側では追加の絞り込みを行わない。</p>
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
        var colorsByUserId = scopeMemberCalendarSettingService.resolveColors(
                ScopeType.TEAM, teamId, memberDtos.stream().map(dto -> dto.userId()).toList());

        var data = memberDtos.stream()
                .map(dto -> new MemberResponse(
                        dto.userId(),
                        dto.displayName(),
                        dto.avatarUrl(),
                        dto.roleName(),
                        dto.joinedAt(),
                        colorsByUserId.get(dto.userId())))
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
            @CacheEvict(value = "team-search", allEntries = true),
            // issue #2496: 論理削除の復元は @SQLRestriction("deleted_at IS NULL") の効き方が反転するため、
            // teamFriendList も失効させる必要がある。
            // deleteTeam で全消し → TTL(30分) の間に誰かが一覧を引くと toView の .orElse(null) により
            // friendTeamName = null がキャッシュされる → restoreTeam しても失効しなければ
            // 復元後もフレンド名が空欄のまま最大 30 分表示され続ける。
            @CacheEvict(value = "teamFriendList", allEntries = true)
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
    // ドメイン間 slug 解決（Todoドメイン等から利用）
    // ========================================

    /**
     * 指定 ID 集合に対して id → slug のマッピングを一括取得する（N+1 回避）。
     *
     * <p>TodoResponseConverter 等の他ドメインが team Entity を直接参照することを防ぐために
     * プリミティブ（Map&lt;Long, String&gt;）のみを返す。Entity は漏らさない。</p>
     *
     * @param ids 取得対象のチーム ID 集合
     * @return id → slug の Map（論理削除済みは除外）。ids が空の場合は空 Map を返す
     */
    public Map<Long, String> getSlugsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return teamRepository.findSlugMapByIdIn(ids);
    }

    /**
     * 指定 ID 集合に対して id → name（チーム名）のマッピングを一括取得する（N+1 回避）。
     *
     * <p>マイページ チームプロジェクト集約（{@code GET /api/v1/me/team-projects}）が、
     * プロジェクトに所属チーム名を付与する際に Entity を直接参照しないよう、プリミティブ
     * （Map&lt;Long, String&gt;）のみを返す。{@link #getSlugsByIds(Collection)} と対をなす。</p>
     *
     * <p>TODO(出陣): 現状は空実装。teamRepository から id → name のマップを論理削除除外で
     * バルク取得して返す実装を /出陣 で行う（findSlugMapByIdIn と同様の name 版を用意する）。</p>
     *
     * @param ids 取得対象のチーム ID 集合
     * @return id → name の Map（論理削除済みは除外）。ids が空の場合は空 Map を返す
     */
    public Map<Long, String> getNamesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return teamRepository.findNameMapByIdIn(ids);
    }

    /**
     * 単一チーム ID から slug を取得する。
     *
     * <p>論理削除済み・存在しない場合は {@code null} を返す（例外を投げない）。</p>
     *
     * @param id チーム ID
     * @return slug 文字列。存在しない場合は null
     */
    public String getSlugById(Long id) {
        if (id == null) {
            return null;
        }
        return teamRepository.findById(id).map(t -> t.getSlug()).orElse(null);
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
        // 柱②-3 検分 P1-2 根治: PROVISIONED（承諾前の事前作成状態）を除外するため
        // ACTIVE 限定クエリへ差し替える。getTeam/resolveTeamId は多数の API の入口であり、
        // 承諾前スコープを認可判定より前に解決できてはならない。
        return teamRepository.findBySlugAndDeletedAtIsNullAndLifecycleStatus(
                        slug, TeamEntity.LifecycleStatus.ACTIVE)
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
                .numericId(team.getId())
                .basicInfo(new TeamResponse.TeamBasicInfoDto(
                        team.getName(), team.getNameKana(),
                        team.getNickname1(), team.getNickname2()))
                .location(new TeamResponse.TeamLocationDto(
                        team.getPrefecture(), team.getCity(), team.getTemplate(),
                        team.getPrefectureCode(), team.getCityCode()))
                .visibility(new TeamResponse.TeamVisibilityDto(
                        team.getVisibility() != null ? team.getVisibility().name() : null,
                        team.getSupporterEnabled()))
                .timezone(team.getTimezone())
                .metadata(new TeamResponse.TeamMetadataDto(
                        team.getVersion(), memberCount,
                        // 画像 URL 根治 Phase 1: icon/banner は生 R2 キーを署名付き表示 URL へ解決する。
                        // mapEmbedUrl は R2 キーではない（Google Maps 埋め込み URL）ため素通し。
                        mediaUrlResolver.resolve(team.getIconUrl()),
                        mediaUrlResolver.resolve(team.getBannerUrl()),
                        team.getMapEmbedUrl()))
                .social(new TeamResponse.TeamSocialDto(teamFriendCount, supporterCount))
                .timestamps(new TeamResponse.TeamTimestampsDto(
                        team.getArchivedAt(), team.getCreatedAt()))
                .build();
    }
}
