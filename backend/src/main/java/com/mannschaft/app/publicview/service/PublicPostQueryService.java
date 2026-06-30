package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.family.CareCategory;
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
 * <p><strong>Phase 2 段階開示</strong>: Controller から {@link ViewerContext} を受け取り、
 * {@link IdentityVisibilityResolver} 経由で §4.6.1 開示マトリクスに従った投稿者識別を返す。
 * ANONYMOUS / NON_MEMBER → 汎用ラベル「投稿者」、SUPPORTER → スコープ設定に従う、
 * MEMBER 以上 → 本名スナップショット（または fullName）を表示する。</p>
 *
 * <p><strong>対象投稿</strong>: 設計書 §4.2 軍議追補に従い blog_posts のみ（ソース直 JOIN 方式）。
 * announcement_feeds / timeline_posts / events は後続軍議で拡張する。</p>
 *
 * <p>スコープ自体の PUBLIC 性確認は本サービスの責務とし、
 * {@code TeamRepository#findPublicTeamById} / {@link OrganizationRepository#findPublicOrganizationById}
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
    private final MediaUrlResolver mediaUrlResolver;

    // ────────────────────────────────────────────────────────────
    // 一覧
    // ────────────────────────────────────────────────────────────

    /**
     * チームの公開投稿一覧を取得する。
     *
     * @param teamId      対象チーム ID
     * @param pageable    ページネーション
     * @param viewerContext 閲覧者コンテキスト（{@link ViewerContextBuilder} で構築済み）
     * @throws BusinessException チームが PUBLIC でない場合（{@link PublicViewErrorCode#PUBLIC_001} 404）
     */
    public Page<PublicPostSummary> listPublicPostsByTeam(Long teamId, Pageable pageable,
                                                         ViewerContext viewerContext) {
        TeamEntity team = teamRepository.findPublicTeamById(teamId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));
        ScopeRef scope = ScopeRef.ofTeam(teamId);
        ScopeSettings settings = scopeSettingsOfTeam(team);
        PublicScopeRef scopeRefDto = PublicScopeRef.ofTeam(teamId, team.getName());

        Page<BlogPostEntity> page = blogPostRepository.findPublicPostsByTeamId(teamId, pageable);
        Map<Long, UserEntity> authors = bulkLoadAuthors(
                page.getContent().stream().map(BlogPostEntity::getAuthorId).toList());

        return page.map(post -> toSummary(post, authors.get(post.getAuthorId()),
                scope, settings, scopeRefDto, viewerContext));
    }

    /**
     * 組織の公開投稿一覧を取得する。
     *
     * @param orgId       対象組織 ID
     * @param pageable    ページネーション
     * @param viewerContext 閲覧者コンテキスト（{@link ViewerContextBuilder} で構築済み）
     */
    public Page<PublicPostSummary> listPublicPostsByOrganization(Long orgId, Pageable pageable,
                                                                  ViewerContext viewerContext) {
        OrganizationEntity org = organizationRepository.findPublicOrganizationById(orgId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));
        ScopeRef scope = ScopeRef.ofOrganization(orgId);
        ScopeSettings settings = scopeSettingsOfOrganization(org);
        PublicScopeRef scopeRefDto = PublicScopeRef.ofOrganization(orgId, org.getName());

        Page<BlogPostEntity> page = blogPostRepository.findPublicPostsByOrganizationId(orgId, pageable);
        Map<Long, UserEntity> authors = bulkLoadAuthors(
                page.getContent().stream().map(BlogPostEntity::getAuthorId).toList());

        return page.map(post -> toSummary(post, authors.get(post.getAuthorId()),
                scope, settings, scopeRefDto, viewerContext));
    }

    // ────────────────────────────────────────────────────────────
    // 詳細
    // ────────────────────────────────────────────────────────────

    /**
     * チームの公開投稿詳細を取得する。
     *
     * @param teamId      対象チーム ID
     * @param postId      投稿 ID
     * @param viewerContext 閲覧者コンテキスト（{@link ViewerContextBuilder} で構築済み）
     * @throws BusinessException チームが PUBLIC でない場合（404）または投稿が存在しない場合
     */
    public PublicPostDetail findPublicPostDetailByTeam(Long teamId, Long postId,
                                                        ViewerContext viewerContext) {
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
        return toDetail(post, author, scope, settings, scopeRefDto, viewerContext);
    }

    /**
     * 組織の公開投稿詳細を取得する。
     *
     * @param orgId       対象組織 ID
     * @param postId      投稿 ID
     * @param viewerContext 閲覧者コンテキスト（{@link ViewerContextBuilder} で構築済み）
     */
    public PublicPostDetail findPublicPostDetailByOrganization(Long orgId, Long postId,
                                                                ViewerContext viewerContext) {
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
        return toDetail(post, author, scope, settings, scopeRefDto, viewerContext);
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
                                        PublicScopeRef scopeRefDto,
                                        ViewerContext viewerContext) {
        PublicAuthorIdentity identity = resolveIdentity(post, author, scope, settings, viewerContext);
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
                                      PublicScopeRef scopeRefDto,
                                      ViewerContext viewerContext) {
        PublicAuthorIdentity identity = resolveIdentity(post, author, scope, settings, viewerContext);
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

    /**
     * 閲覧者コンテキストを使って投稿者識別を解決する。
     *
     * <p>Phase 2: ViewerContext を受け取って §4.6.1 マトリクスに従い段階開示を適用する。
     * {@code author.fullName} は users.lastName + users.firstName の連結として構築する。</p>
     */
    private PublicAuthorIdentity resolveIdentity(BlogPostEntity post, UserEntity author,
                                                  ScopeRef scope, ScopeSettings settings,
                                                  ViewerContext viewerContext) {
        String fullName = buildFullName(author);
        boolean isMinor = author != null && CareCategory.MINOR == author.getCareCategory();
        PostAuthor postAuthor = new PostAuthor(
                post.getAuthorId(),
                author != null ? author.getDisplayName() : null,
                post.getAuthorRealNameSnapshot(),
                fullName,
                // 画像 URL 根治 Phase 2: 生 R2 キーを署名付き表示 URL へ解決してから識別解決へ渡す
                author != null ? mediaUrlResolver.resolve(author.getAvatarUrl()) : null,
                isMinor);
        DisplayIdentity result = identityVisibilityResolver.resolveIdentityForViewer(
                postAuthor, viewerContext, scope, settings);
        return new PublicAuthorIdentity(
                result.displayLabel(),
                result.avatarUrl(),
                result.teamAffiliationVisible(),
                result.anonymized());
    }

    /**
     * UserEntity から本名（lastName + firstName）を組み立てる。
     *
     * <p>null の場合は null を返す。どちらか一方が null の場合は非 null 側のみを返す。</p>
     */
    private static String buildFullName(UserEntity author) {
        if (author == null) {
            return null;
        }
        String last = author.getLastName();
        String first = author.getFirstName();
        if (last == null && first == null) {
            return null;
        }
        if (last == null) {
            return first;
        }
        if (first == null) {
            return last;
        }
        return last + first;
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
