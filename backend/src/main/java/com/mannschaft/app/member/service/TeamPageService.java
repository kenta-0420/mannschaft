package com.mannschaft.app.member.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.MemberErrorCode;
import com.mannschaft.app.member.MemberSubtabKey;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.PageStatus;
import com.mannschaft.app.member.PageType;
import com.mannschaft.app.member.PageVisibility;
import com.mannschaft.app.member.dto.CreateTeamPageRequest;
import com.mannschaft.app.member.dto.PreviewTokenResponse;
import com.mannschaft.app.member.dto.PublishRequest;
import com.mannschaft.app.member.dto.SectionResponse;
import com.mannschaft.app.member.dto.MemberProfileResponse;
import com.mannschaft.app.member.dto.TeamPageResponse;
import com.mannschaft.app.member.dto.UpdateTeamPageRequest;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import com.mannschaft.app.member.entity.MemberProfileEntity;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * メンバー紹介ページサービス。ページのCRUD・公開管理・プレビュートークンを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamPageService {

    private final TeamPageRepository pageRepository;
    private final TeamPageSectionRepository sectionRepository;
    private final MemberProfileRepository profileRepository;
    private final MemberMapper memberMapper;
    private final AccessControlService accessControlService;
    private final MemberSubtabVisibilityService memberSubtabVisibilityService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /**
     * ページ一覧をページング取得する。teamId/organizationId は呼び出し元が明示的に指定するスコープの
     * ため、非所属者は 403（COMMON_002）で拒否する（Wave3-B2 member 認可根治）。
     *
     * <p>CMP-260919-1140 Phase 1: 組織スコープは「紹介」サブタブの外側の門
     * （{@link MemberSubtabVisibilityService#assertViewable}）で判定する。既定値（MEMBER）は
     * 従来の {@code checkMembership} と等価。チームスコープは Phase 1 対象外のため従来どおり
     * {@code checkMembership} を維持する。下書き（DRAFT）ページは ADMIN 以外には一覧に出さない
     * （内側の扉。設計書 §5 合成ルール）。</p>
     *
     * <p>検分修正（P1）: サブタブが PUBLIC 設定でも、それは「サブタブという入口」の可視性に過ぎず、
     * ページ個別の {@code visibility}（{@link PageVisibility#MEMBERS_ONLY}）までは緩めない（AND 条件。
     * 設計書 F06.2 §アクセス制御ロジック 1081-1082 行）。非会員（サブタブ門は通過したがスコープの
     * メンバーではない）には {@code MEMBERS_ONLY} ページを列挙させない。</p>
     */
    public Page<TeamPageResponse> listPages(Long actorUserId, Long teamId, Long organizationId, Pageable pageable) {
        boolean isAdmin;
        boolean isMember = false;
        if (teamId != null) {
            accessControlService.checkMembership(actorUserId, teamId, SCOPE_TEAM);
            isAdmin = accessControlService.isAdminOrAbove(actorUserId, teamId, SCOPE_TEAM);
        } else {
            memberSubtabVisibilityService.assertViewable(
                    actorUserId, ScopeType.ORGANIZATION, organizationId, MemberSubtabKey.MEMBER_PROFILES);
            // 検分修正（3巡目・P2）: assertViewable の外側の門は SYSTEM_ADMIN を無条件バイパスする
            // （設計書 F06.6 §9.1）。一覧の内側の判定も揃え、所属のない SYSTEM_ADMIN を ADMIN 同様に
            // 下書き含む全件取得させる（isAdminOrAbove だけだと SYSTEM_ADMIN は非会員扱いになり、
            // 外側の門のバイパスと矛盾して公開済みのみに縮退していた）。
            isAdmin = accessControlService.isSystemAdmin(actorUserId)
                    || accessControlService.isAdminOrAbove(actorUserId, organizationId, SCOPE_ORGANIZATION);
            if (!isAdmin) {
                // 検分指摘A（P1）: isMember() は所属の有無（SUPPORTER も true）を見るだけで、
                // F06.2 の「MEMBERS_ONLY = MEMBER 以上」という仕様のロール閾値と一致しない。
                // hasRoleOrAbove(...,"MEMBER") で SUPPORTER を除外する。
                isMember = accessControlService.hasRoleOrAbove(
                        actorUserId, organizationId, SCOPE_ORGANIZATION, "MEMBER");
            }
        }
        Page<TeamPageEntity> page;
        if (teamId != null) {
            page = pageRepository.findByTeamIdOrderBySortOrder(teamId, pageable);
        } else if (isAdmin) {
            page = pageRepository.findByOrganizationIdOrderBySortOrder(organizationId, pageable);
        } else if (isMember) {
            // 内側の扉: DRAFT ページは非管理者の一覧から除外（組織スコープのみ・Phase 1）
            page = pageRepository.findByOrganizationIdAndStatusOrderBySortOrder(
                    organizationId, PageStatus.PUBLISHED, pageable);
        } else {
            // 非会員（サブタブ門は通過したがスコープ非所属）: ページ個別 visibility=PUBLIC のみ列挙可
            page = pageRepository.findByOrganizationIdAndStatusAndVisibilityOrderBySortOrder(
                    organizationId, PageStatus.PUBLISHED, PageVisibility.PUBLIC, pageable);
        }
        return page.map(memberMapper::toTeamPageResponse);
    }

    /**
     * ページ詳細をセクション・メンバー付きで取得する。
     *
     * <p>URL に teamId/organizationId を含まない bare id エンドポイントのため、entity 由来スコープで
     * 認可判定し、非所属者には 404（PAGE_NOT_FOUND）で存在秘匿する（Wave3-B2 member BOLA対策）。</p>
     */
    public TeamPageResponse getPage(Long actorUserId, Long pageId) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        checkPageMembershipOrNotFound(actorUserId, entity);
        List<TeamPageSectionEntity> sections = sectionRepository.findByTeamPageIdOrderBySortOrder(pageId);
        List<MemberProfileEntity> members = profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(pageId);

        List<SectionResponse> sectionResponses = memberMapper.toSectionResponseList(sections);
        List<MemberProfileResponse> memberResponses = memberMapper.toMemberProfileResponseList(members);

        return memberMapper.toTeamPageDetailResponse(entity, sectionResponses, memberResponses);
    }

    /**
     * ページを作成する。
     */
    @Transactional
    public TeamPageResponse createPage(Long userId, CreateTeamPageRequest request) {
        PageType pageType = EnumInputParser.parse(PageType.class, request.getPageType(), "pageType");
        Long teamId = request.getTeamId();
        Long organizationId = request.getOrganizationId();

        // Wave3-B2 member 認可根治: 作成先スコープは呼び出し元が明示的に指定するため checkAdminOrAbove（403）
        if (teamId != null) {
            accessControlService.checkAdminOrAbove(userId, teamId, SCOPE_TEAM);
        } else {
            accessControlService.checkAdminOrAbove(userId, organizationId, SCOPE_ORGANIZATION);
        }

        // メインページの重複チェック
        if (pageType == PageType.MAIN) {
            if (teamId != null && pageRepository.findByTeamIdAndPageType(teamId, PageType.MAIN).isPresent()) {
                throw new BusinessException(MemberErrorCode.DUPLICATE_MAIN_PAGE);
            }
            if (organizationId != null && pageRepository.findByOrganizationIdAndPageType(organizationId, PageType.MAIN).isPresent()) {
                throw new BusinessException(MemberErrorCode.DUPLICATE_MAIN_PAGE);
            }
        }

        // 年度重複チェック
        if (pageType == PageType.YEARLY && request.getYear() != null) {
            if (teamId != null && pageRepository.existsByTeamIdAndYear(teamId, request.getYear())) {
                throw new BusinessException(MemberErrorCode.DUPLICATE_YEAR);
            }
            if (organizationId != null && pageRepository.existsByOrganizationIdAndYear(organizationId, request.getYear())) {
                throw new BusinessException(MemberErrorCode.DUPLICATE_YEAR);
            }
        }

        // スラッグ重複チェック
        if (teamId != null && pageRepository.existsByTeamIdAndSlug(teamId, request.getSlug())) {
            throw new BusinessException(MemberErrorCode.DUPLICATE_SLUG);
        }
        if (organizationId != null && pageRepository.existsByOrganizationIdAndSlug(organizationId, request.getSlug())) {
            throw new BusinessException(MemberErrorCode.DUPLICATE_SLUG);
        }

        PageVisibility visibility = request.getVisibility() != null
                ? EnumInputParser.parse(PageVisibility.class, request.getVisibility(), "visibility") : PageVisibility.MEMBERS_ONLY;

        TeamPageEntity entity = TeamPageEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .title(request.getTitle())
                .slug(request.getSlug())
                .pageType(pageType)
                .year(request.getYear())
                .description(request.getDescription())
                .coverImageS3Key(request.getCoverImageS3Key())
                .visibility(visibility)
                .createdBy(userId)
                .build();

        TeamPageEntity saved = pageRepository.save(entity);
        log.info("ページ作成: id={}, pageType={}", saved.getId(), pageType);
        return memberMapper.toTeamPageResponse(saved);
    }

    /**
     * ページを更新する。
     */
    @Transactional
    public TeamPageResponse updatePage(Long actorUserId, Long pageId, UpdateTeamPageRequest request) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        checkPageAdminOrNotFound(actorUserId, entity);

        PageVisibility visibility = request.getVisibility() != null
                ? EnumInputParser.parse(PageVisibility.class, request.getVisibility(), "visibility") : entity.getVisibility();
        Boolean allowSelfEdit = request.getAllowSelfEdit() != null
                ? request.getAllowSelfEdit() : entity.getAllowSelfEdit();
        Integer sortOrder = request.getSortOrder() != null
                ? request.getSortOrder() : entity.getSortOrder();

        entity.update(request.getTitle(), request.getSlug(), request.getDescription(),
                request.getCoverImageS3Key(), visibility, allowSelfEdit, sortOrder);

        TeamPageEntity saved = pageRepository.save(entity);
        log.info("ページ更新: id={}", pageId);
        return memberMapper.toTeamPageResponse(saved);
    }

    /**
     * ページを論理削除する。
     */
    @Transactional
    public void deletePage(Long actorUserId, Long pageId) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        checkPageAdminOrNotFound(actorUserId, entity);
        entity.softDelete();
        pageRepository.save(entity);
        log.info("ページ削除: id={}", pageId);
    }

    /**
     * 公開ステータスを変更する。
     */
    @Transactional
    public TeamPageResponse changeStatus(Long actorUserId, Long pageId, PublishRequest request) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        checkPageAdminOrNotFound(actorUserId, entity);
        PageStatus status = EnumInputParser.parse(PageStatus.class, request.getStatus(), "status");
        entity.changeStatus(status);
        TeamPageEntity saved = pageRepository.save(entity);
        log.info("ページステータス変更: id={}, status={}", pageId, status);
        return memberMapper.toTeamPageResponse(saved);
    }

    /**
     * プレビュートークンを発行する。
     */
    @Transactional
    public PreviewTokenResponse issuePreviewToken(Long actorUserId, Long pageId) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        checkPageAdminOrNotFound(actorUserId, entity);

        byte[] tokenBytes = new byte[48];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(24);

        entity.setPreviewToken(token, expiresAt);
        pageRepository.save(entity);

        String previewUrl = String.format("/team/pages/%s?preview_token=%s", entity.getSlug(), token);
        log.info("プレビュートークン発行: pageId={}", pageId);

        return new PreviewTokenResponse(entity.getId(), token, previewUrl, expiresAt);
    }

    /**
     * プレビュートークンを無効化する。
     */
    @Transactional
    public void revokePreviewToken(Long actorUserId, Long pageId) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        checkPageAdminOrNotFound(actorUserId, entity);
        entity.clearPreviewToken();
        pageRepository.save(entity);
        log.info("プレビュートークン無効化: pageId={}", pageId);
    }

    /**
     * ページエンティティを取得する。存在しない場合は例外をスローする。
     */
    TeamPageEntity findPageOrThrow(Long pageId) {
        return pageRepository.findById(pageId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.PAGE_NOT_FOUND));
    }

    /**
     * ページ entity 由来スコープでメンバー（または ADMIN 以上）であることを検証する（閲覧系）。
     *
     * <p>URL に teamId/organizationId を含まない bare id エンドポイント向け。checkMembership の
     * ような 403（COMMON_002）ではなく、非所属者には 404（PAGE_NOT_FOUND）で存在秘匿する
     * （Wave3-B2 member BOLA対策。workflow ドメイン {@code WorkflowApprovalService#decide} 踏襲）。
     * {@link TeamPageSectionService}/{@link MemberProfileService} からも再利用する（同一パッケージ）。</p>
     */
    void checkPageMembershipOrNotFound(Long actorUserId, TeamPageEntity page) {
        Long scopeId = resolveScopeId(page);
        String scopeType = resolveScopeType(page);

        // ADMIN/DEPUTY_ADMIN 以上は常に全ページ閲覧可（下書き含む）
        if (accessControlService.isAdminOrAbove(actorUserId, scopeId, scopeType)) {
            return;
        }

        // CMP-260919-1140 Phase 1: 組織スコープは「紹介」サブタブの外側の門（min_role）で判定する。
        // 既定値（MEMBER）は従来の isMember 判定と等価。両方（外側の門＋内側の扉）を通った人だけ見える。
        // チームスコープは Phase 1 対象外のため従来どおり isMember のみを維持する（下書き判定も対象外。
        // DRAFT ブロックを全スコープに広げると Wave3-B2 の既存 TEAM スコープ挙動を壊すため、
        // 内側の扉（DRAFT 非表示）は組織スコープ限定で適用する）。
        if (SCOPE_ORGANIZATION.equals(scopeType)) {
            // 検分修正（3巡目・P2）: assertViewable の外側の門は SYSTEM_ADMIN を無条件バイパスする
            // （設計書 F06.6 §9.1 に明記）。内側の判定（DRAFT 秘匿・visibility 判定）が
            // isAdminOrAbove/hasRoleOrAbove のみに基づくと、所属のない SYSTEM_ADMIN がここで
            // 弾かれ、外側の門のバイパスと矛盾する（一覧は公開済みのみ・詳細は DRAFT が 404 になる）。
            // SYSTEM_ADMIN は ADMIN と同様に全ページ閲覧可とする。
            if (accessControlService.isSystemAdmin(actorUserId)) {
                return;
            }
            // 内側の扉: 下書き（DRAFT）ページは ADMIN 以外の誰にも見せない（設計書 §5 合成ルール）
            if (page.getStatus() == PageStatus.DRAFT) {
                throw new BusinessException(MemberErrorCode.PAGE_NOT_FOUND);
            }
            try {
                memberSubtabVisibilityService.assertViewable(
                        actorUserId, ScopeType.ORGANIZATION, scopeId, MemberSubtabKey.MEMBER_PROFILES);
            } catch (BusinessException ex) {
                // Wave3-B2 member BOLA対策の 404 秘匿パターンを維持（403 ではなく 404 を返す）
                throw new BusinessException(MemberErrorCode.PAGE_NOT_FOUND);
            }
            // 検分修正（P1）: サブタブの外側の門（min_role）は「サブタブという入口」の可視性であり、
            // ページ個別の visibility（MEMBERS_ONLY）までは緩めない（AND 条件。設計書 F06.2
            // §アクセス制御ロジック 1081-1082 行）。サブタブが PUBLIC 設定でも、ページが
            // MEMBERS_ONLY なら非会員は拒否する。
            //
            // 検分指摘A（2巡目・P1）: 判定は isMember()（所属の有無。SUPPORTER も true）ではなく
            // ロール閾値（MEMBER 以上）で行う。isMember() のままだと SUPPORTER も MEMBERS_ONLY を
            // 閲覧できてしまい、F06.2 の「MEMBERS_ONLY = MEMBER 以上」仕様に反する。
            if (page.getVisibility() == PageVisibility.MEMBERS_ONLY
                    && !accessControlService.hasRoleOrAbove(actorUserId, scopeId, scopeType, "MEMBER")) {
                throw new BusinessException(MemberErrorCode.PAGE_NOT_FOUND);
            }
            return;
        }

        if (!accessControlService.isMember(actorUserId, scopeId, scopeType)) {
            throw new BusinessException(MemberErrorCode.PAGE_NOT_FOUND);
        }
    }

    /**
     * ページ entity 由来スコープでアクターが ADMIN/DEPUTY_ADMIN 以上かどうかを判定する（真偽値のみ・例外なし）。
     *
     * <p>検分修正（3巡目・P1）: {@link MemberProfileService#listProfiles}/{@code getProfile} から、
     * 非表示（{@code is_visible = false}）プロフィールの除外要否を切り替えるために公開する。
     * 管理者は編集用途のため非表示行も含めて閲覧できる必要があり、それ以外（会員・非会員問わず）は
     * 非表示行を除外する。</p>
     *
     * <p>検分修正（4巡目・P2）: {@code isAdminOrAbove} は SYSTEM_ADMIN を含まない（ADMIN_ROLES =
     * {@code {"ADMIN","DEPUTY_ADMIN"}}）。{@link #checkPageMembershipOrNotFound} は組織スコープで
     * SYSTEM_ADMIN を無条件バイパスするのに、本メソッドがバイパスしないと、所属のない SYSTEM_ADMIN は
     * 一覧で非表示行が欠け、非表示プロフィールの詳細取得が 404 になる（前段の到達判定と矛盾する）。
     * 同一 PR で新設した SYSTEM_ADMIN バイパス（{@link #listPages}・{@link #checkPageMembershipOrNotFound}）
     * と扱いを揃える。</p>
     */
    boolean isPageAdmin(Long actorUserId, TeamPageEntity page) {
        return accessControlService.isSystemAdmin(actorUserId)
                || accessControlService.isAdminOrAbove(actorUserId, resolveScopeId(page), resolveScopeType(page));
    }

    /**
     * ページ entity 由来スコープで ADMIN/DEPUTY_ADMIN 以上であることを検証する（変更系）。
     * 非所属者には 404（PAGE_NOT_FOUND）で存在秘匿する（Wave3-B2 member BOLA対策）。
     */
    void checkPageAdminOrNotFound(Long actorUserId, TeamPageEntity page) {
        Long scopeId = resolveScopeId(page);
        String scopeType = resolveScopeType(page);
        if (!accessControlService.isAdminOrAbove(actorUserId, scopeId, scopeType)) {
            throw new BusinessException(MemberErrorCode.PAGE_NOT_FOUND);
        }
    }

    private Long resolveScopeId(TeamPageEntity page) {
        return page.getTeamId() != null ? page.getTeamId() : page.getOrganizationId();
    }

    private String resolveScopeType(TeamPageEntity page) {
        return page.getTeamId() != null ? SCOPE_TEAM : SCOPE_ORGANIZATION;
    }
}
