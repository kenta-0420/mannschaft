package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.media.BlogMediaScope;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
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
import com.mannschaft.app.publicview.visibility.ViewerStatus;
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
    // TODO: publicview ドメインが payment ドメインを参照（CLAUDE.md 原則5）。クロスドメイン FK は張らず
    //       PaymentGateService のメソッド呼び（ID 渡し）に限定する。将来はイベント駆動化を検討。
    private final PaymentGateService paymentGateService;

    /** 記事本文（Markdown）に埋め込まれた r2Key を署名付き表示 URL へ解決する部品。 */
    private final BlogBodyMediaResolver blogBodyMediaResolver;

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
                // 一覧サマリは excerpt のみを露出する。excerpt 未設定時に body 先頭を出すと
                // 有料本文が一覧経由で漏洩するため、body フォールバックは廃止する（F08.9 漏洩封鎖）。
                truncate(post.getExcerpt(), 200),
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
        String title = post.getTitle();
        // 公開（未認証）経路も表示経路なので、マスクを免れた本文の r2Key を署名 URL へ解決する。
        String bodyHtml = resolveBodyMedia(post, applyPaywallToPublicDetail(post, viewerContext));
        // titleHidden 相当（=null 返却）のケースは applyPaywallToPublicDetail が PUBLIC_003 で 404 済み。
        return new PublicPostDetail(
                PublicPostSummary.SOURCE_TYPE_BLOG_POST,
                post.getId(),
                title,
                bodyHtml,
                identity,
                scopeRefDto,
                toOffsetDateTime(post.getPublishedAt())
        );
    }

    /**
     * 公開詳細の本文について、生の r2Key を署名付き表示 URL へ解決する。
     *
     * <p>{@code PublicPostDetail.bodyHtml} は名前に反して生 Markdown であり、フロントエンドは
     * {@code sanitizeHtml} のみで描画する。ゆえに BE 側で解決しなければ画像は表示されない。</p>
     *
     * <p>ペイウォールでマスクされた本文（{@code null}）は解決しない
     * （マスクを解決処理で復活させてはならない）。</p>
     *
     * @param post     対象記事（スコープ導出に使用）
     * @param bodyHtml ペイウォール適用後の本文（マスク時は null）
     * @return 署名 URL 解決後の本文。マスク時は null のまま
     */
    private String resolveBodyMedia(BlogPostEntity post, String bodyHtml) {
        if (bodyHtml == null) {
            return null;
        }
        BlogMediaScope scope = BlogMediaScope.of(
                post.getTeamId(), post.getOrganizationId(), post.getUserId());
        if (scope == null) {
            log.warn("本文メディア: 記事のスコープを判定できないため解決を見送る: postId={}", post.getId());
            return bodyHtml;
        }
        return blogBodyMediaResolver.resolveBody(bodyHtml, scope.scopeType(), scope.scopeId());
    }

    /**
     * 公開詳細（未認証 permitAll）にペイウォール本文ゲートを適用する（F08.9 漏洩根治）。
     *
     * <p>判定の単一真実源は {@link PaymentGateService#checkAccess(String, Long, Long)}
     * （content-gates/check と同一）。未認証公開経路は「存在秘匿」原則のため、
     * {@code titleHidden=true} かつ未課金の場合は 200 でマスクせず <b>404（{@code PUBLIC_003}）</b> にする。
     * {@code titleHidden=false} かつ未課金なら bodyHtml=null（title は残す）で 200 を返す。</p>
     *
     * <ul>
     *   <li>著者本人・SystemAdmin はゲート無視で全文。</li>
     *   <li>fail-closed: {@code checkAccess} が例外 → ゲート行有りなら bodyHtml=null、
     *       ゲート行無しなら従来どおり body を返す。</li>
     * </ul>
     *
     * @return 露出してよい bodyHtml（マスク時は null）
     * @throws BusinessException titleHidden かつ未課金の場合（{@code PUBLIC_003}、404）
     */
    private String applyPaywallToPublicDetail(BlogPostEntity post, ViewerContext viewerContext) {
        Long viewerUserId = viewerContext.userId();
        // 著者本人はゲート無視で全文
        if (viewerUserId != null && viewerUserId.equals(post.getAuthorId())) {
            return post.getBody();
        }
        // SystemAdmin はゲート無視で全文
        if (viewerContext.status() == ViewerStatus.SYSTEM_ADMIN) {
            return post.getBody();
        }

        GateCheckResponse gate;
        try {
            gate = paymentGateService.checkAccess(ContentGateType.POST, post.getId(), viewerUserId);
        } catch (Exception e) {
            // 評価不能（例外）→ null 扱いで fail-closed 経路へ統一する。
            log.warn("ペイウォール判定失敗（公開詳細）: postId={} → fail-closed 判定へ", post.getId(), e);
            gate = null;
        }

        // checkAccess が null／例外のいずれでも、ゲート行が有るなら本文をマスク、無いなら従来どおり返す。
        if (gate == null) {
            return safelyHasGate(post.getId()) ? null : post.getBody();
        }
        if (gate.isAccessible()) {
            return post.getBody();
        }
        // 未課金: titleHidden なら存在秘匿で 404、それ以外は body のみマスク
        if (gate.isTitleHidden()) {
            throw new BusinessException(PublicViewErrorCode.PUBLIC_003);
        }
        return null;
    }

    /**
     * ゲート存在確認（fail-closed 判定用）。存在確認自体が失敗した場合は過剰遮断を避け false（非課金扱い）を返す。
     */
    private boolean safelyHasGate(Long postId) {
        try {
            return paymentGateService.hasGate(ContentGateType.POST, postId);
        } catch (Exception e) {
            log.warn("ペイウォールゲート存在確認に失敗: postId={} → ゲート無し扱い", postId, e);
            return false;
        }
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
