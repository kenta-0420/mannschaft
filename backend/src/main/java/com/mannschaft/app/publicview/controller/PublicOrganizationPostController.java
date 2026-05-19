package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.publicview.dto.PublicPostDetail;
import com.mannschaft.app.publicview.dto.PublicPostSummary;
import com.mannschaft.app.publicview.service.PublicPostQueryService;
import com.mannschaft.app.publicview.service.ViewerContextBuilder;
import com.mannschaft.app.publicview.visibility.ViewerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F19.1 公開組織投稿 Controller。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.1 / §7.3</p>
 *
 * <p>{@link PublicTeamPostController} の組織版。Phase 2 では {@link ViewerContextBuilder} を使って
 * ログイン済みユーザーの閲覧立場を判定し、段階開示ルール（§4.6.1 マトリクス）に従った投稿者識別を返す。
 * blog_posts のみ対応（§4.2 軍議追補）。</p>
 */
@RestController
@RequestMapping("/api/v1/public/organizations/{orgId}/posts")
@Tag(name = "公開組織投稿 API (F19.1)")
@RequiredArgsConstructor
public class PublicOrganizationPostController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final PublicPostQueryService publicPostQueryService;
    private final ViewerContextBuilder viewerContextBuilder;

    /**
     * 組織の公開投稿一覧を取得する。
     *
     * @param authentication Spring Security の Authentication（未ログインなら null / anonymous）
     */
    @GetMapping
    @Operation(
            summary = "組織の公開投稿一覧（未ログイン公開）",
            description = "PUBLIC 組織の PUBLIC / PUBLISHED ブログ記事一覧。"
                    + " ログイン済みの場合は段階開示ルールに従った投稿者識別を返す。"
                    + " PRIVATE 組織の ID で叩いた場合は 404（IDOR 対策で隠蔽）。")
    public Page<PublicPostSummary> listPublicPosts(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        if (size <= 0) {
            pageable = PageRequest.of(Math.max(page, 0), DEFAULT_PAGE_SIZE);
        }
        ViewerContext viewerContext = viewerContextBuilder.buildForOrganization(authentication, orgId);
        return publicPostQueryService.listPublicPostsByOrganization(orgId, pageable, viewerContext);
    }

    /**
     * 組織の公開投稿詳細を取得する。
     *
     * @param authentication Spring Security の Authentication（未ログインなら null / anonymous）
     */
    @GetMapping("/{postId}")
    @Operation(
            summary = "組織の公開投稿詳細（未ログイン公開）",
            description = "PUBLIC 組織の PUBLIC / PUBLISHED ブログ記事詳細。"
                    + " ログイン済みの場合は段階開示ルールに従った投稿者識別を返す。"
                    + " PRIVATE 組織 / 非公開記事 / 不在は 404。")
    public PublicPostDetail getPublicPostDetail(
            @PathVariable Long orgId,
            @PathVariable Long postId,
            Authentication authentication) {
        ViewerContext viewerContext = viewerContextBuilder.buildForOrganization(authentication, orgId);
        return publicPostQueryService.findPublicPostDetailByOrganization(orgId, postId, viewerContext);
    }
}
