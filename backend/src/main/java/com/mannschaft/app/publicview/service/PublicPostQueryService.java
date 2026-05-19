package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.PublicAuthorIdentity;
import com.mannschaft.app.publicview.dto.PublicPostDetail;
import com.mannschaft.app.publicview.dto.PublicPostSummary;
import com.mannschaft.app.publicview.dto.PublicScopeRef;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.visibility.DisplayIdentity;
import com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver;
import com.mannschaft.app.publicview.visibility.PostAuthor;
import com.mannschaft.app.publicview.visibility.ScopeRef;
import com.mannschaft.app.publicview.visibility.ScopeSettings;
import com.mannschaft.app.publicview.visibility.ViewerContext;
import com.mannschaft.app.team.entity.TeamEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F19.1 公開投稿クエリサービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.1 / §7.6</p>
 *
 * <p><strong>Phase 1 スコープ</strong>: 設計書 §4.2 軍議追補に従い、本実装は
 * <strong>blog_posts のみ</strong> を対象とする（ソース直 JOIN 方式）。
 * announcement_feeds は経由しない（事後同期未実装のため安全側に倒す）。
 * timeline_posts / events は後続軍議で拡張する。</p>
 *
 * <p><strong>段階開示</strong>: Phase 1 では公開エンドポイント = 未ログイン想定のため、
 * 内部で {@link ViewerContext#anonymous()} を構築し
 * {@link IdentityVisibilityResolver} を経由して投稿者識別を汎用ラベルに固定する
 * （§4.6.1 マトリクス）。ログイン済み閲覧者向けの段階開示は Phase 2 で
 * ViewerContextBuilder 経由に拡張する。</p>
 *
 * <p>スコープ自体の PUBLIC 性確認は本サービスの責務とし、
 * {@link TeamRepository#findPublicTeamById} / {@link OrganizationRepository#findPublicOrganizationById}
 * の戻り値で判定する（PRIVATE / archived / 削除済 / 不在は 404 で隠蔽）。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PublicPostQueryService {

    private final BlogPostRepository blogPostRepository;
    private final UserRepository userRepository;
    private final com.mannschaft.app.team.repository.TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final IdentityVisibilityResolver identityVisibilityResolver;

    // ────────────────────────────────────────────────────────────
    // 一覧
    // ────────────────────────────────────────────────────────────

    /**
     * チームの公開投稿一覧を取得する。
     *
     * @throws BusinessException チームが PUBLIC でない場合（{@link PublicViewErrorCode#PUBLIC_001} 404）
     */
    public Page<PublicPostSummary> listPublicPostsByTeam(Long teamId, Pageable pageable) {
        TeamEntity team = teamRepository.findPublicTeamById(teamId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));
        ScopeRef scope = ScopeRef.ofTeam(teamId);
        ScopeSettings settings = scopeSettingsOfTeam(team);
        PublicScopeRef scopeRefDto = PublicScopeRef.ofTeam(teamId, team.getName());

        Page<BlogPostEntity> page = blogPostRepository.findPublicPostsByTeamId(teamId, pageable);
        Map<Long, UserEntity> authors = bulkLoadAuthors(
                page.getContent().stream().map(BlogPostEntity::getAuthorId).toList());

        return page.map(post -> toSummary(post, authors.get(post.getAuthorId()),
                scope, settings, scopeRefDto));
    }

    /**
     * 組織の公開投稿一覧を取得する。
     */
    public Page<PublicPostSummary> listPublicPostsByOrganization(Long orgId, Pageable pageable) {
        OrganizationEntity org = organizationRepository.findPublicOrganizationById(orgId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));
        ScopeRef scope = ScopeRef.ofOrganization(orgId);
        ScopeSettings settings = scopeSettingsOfOrganization(org);
        PublicScopeRef scopeRefDto = PublicScopeRef.ofOrganization(orgId, org.getName());

        Page<BlogPostEntity> page = blogPostRepository.findPublicPostsByOrganizationId(orgId, pageable);
        Map<Long, UserEntity> authors = bulkLoadAuthors(
                page.getContent().stream().map(BlogPostEntity::getAuthorId).toList());

        return page.map(post -> toSummary(post, authors.get(post.getAuthorId()),
                scope, settings, scopeRefDto));
    }

    // ────────────────────────────────────────────────────────────
    // 詳細
    // ────────────────────────────────────────────────────────────

    /**
     * チームの公開投稿詳細を取得する。
     *
     * @throws BusinessException チームが PUBLIC でない場合（404）または投稿が存在しない場合
     */
    public PublicPostDetail findPublicPostDetailByTeam(Long teamId, Long postId) {
        TeamEntity team = teamRepository.findPublicTeamById(teamId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));
        BlogPostEntity post = blogPostRepository.findPublicPostByTeamIdAndId(teamId, postId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_003));
        ScopeRef scope = ScopeRef.ofTeam(teamId);
        ScopeSettings settings = scopeSettingsOfTeam(team);
        PublicScopeRef scopeRefDto = PublicScopeRef.ofTeam(teamId, team.getName());
        UserEntity author = post.getAuthorId() != null
                ? userRepository.findById(post.getAuthorId()).orElse(null)
                : null;
        return toDetail(post, author, scope, settings, scopeRefDto);
    }

    /**
     * 組織の公開投稿詳細を取得する。
     */
    public PublicPostDetail findPublicPostDetailByOrganization(Long orgId, Long postId) {
        OrganizationEntity org = organizationRepository.findPublicOrganizationById(orgId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));
        BlogPostEntity post = blogPostRepository.findPublicPostByOrganizationIdAndId(orgId, postId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_003));
        ScopeRef scope = ScopeRef.ofOrganization(orgId);
        ScopeSettings settings = scopeSettingsOfOrganization(org);
        PublicScopeRef scopeRefDto = PublicScopeRef.ofOrganization(orgId, org.getName());
        UserEntity author = post.getAuthorId() != null
                ? userRepository.findById(post.getAuthorId()).orElse(null)
                : null;
        return toDetail(post, author, scope, settings, scopeRefDto);
    }

    // ────────────────────────────────────────────────────────────
    // 内部ヘルパ
    // ────────────────────────────────────────────────────────────

    private Map<Long, UserEntity> bulkLoadAuthors(Collection<Long> authorIds) {
        Set<Long> nonNull = authorIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (nonNull.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserEntity> result = new HashMap<>();
        for (UserEntity u : userRepository.findByIdIn(nonNull)) {
            result.put(u.getId(), u);
        }
        return result;
    }

    private ScopeSettings scopeSettingsOfTeam(TeamEntity team) {
        return new ScopeSettings(team.getSupporterNameDisclosure() != null
                ? team.getSupporterNameDisclosure()
                : NameDisclosureMode.DISPLAY_NAME);
    }

    private ScopeSettings scopeSettingsOfOrganization(OrganizationEntity org) {
        return new ScopeSettings(org.getSupporterNameDisclosure() != null
                ? org.getSupporterNameDisclosure()
                : NameDisclosureMode.DISPLAY_NAME);
    }

    private PublicPostSummary toSummary(BlogPostEntity post, UserEntity author,
                                        ScopeRef scope, ScopeSettings settings,
                                        PublicScopeRef scopeRefDto) {
        PublicAuthorIdentity identity = resolveIdentity(post, author, scope, settings);
        return new PublicPostSummary(
                PublicPostSummary.SOURCE_TYPE_BLOG_POST,
                post.getId(),
                post.getTitle(),
                truncate(post.getExcerpt() != null ? post.getExcerpt() : post.getBody(), 200),
                identity,
                scopeRefDto,
                toOffsetDateTime(post.getPublishedAt())
        );
    }

    private PublicPostDetail toDetail(BlogPostEntity post, UserEntity author,
                                      ScopeRef scope, ScopeSettings settings,
                                      PublicScopeRef scopeRefDto) {
        PublicAuthorIdentity identity = resolveIdentity(post, author, scope, settings);
        return new PublicPostDetail(
                PublicPostSummary.SOURCE_TYPE_BLOG_POST,
                post.getId(),
                post.getTitle(),
                post.getBody(),
                identity,
                scopeRefDto,
                toOffsetDateTime(post.getPublishedAt())
        );
    }

    private PublicAuthorIdentity resolveIdentity(BlogPostEntity post, UserEntity author,
                                                 ScopeRef scope, ScopeSettings settings) {
        PostAuthor postAuthor = new PostAuthor(
                post.getAuthorId(),
                author != null ? author.getDisplayName() : null,
                null, // realNameSnapshot は Phase 2 で導入
                author != null ? author.getAvatarUrl() : null);
        // Phase 1: 公開エンドポイントは未ログイン想定 → ANONYMOUS で固定
        ViewerContext viewer = ViewerContext.anonymous();
        DisplayIdentity result = identityVisibilityResolver.resolveIdentityForViewer(
                postAuthor, viewer, scope, settings);
        return new PublicAuthorIdentity(
                result.displayLabel(),
                result.avatarUrl(),
                result.teamAffiliationVisible(),
                result.anonymized());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static OffsetDateTime toOffsetDateTime(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return ldt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
