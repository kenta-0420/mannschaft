package com.mannschaft.app.knowledgebase.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.knowledgebase.KnowledgeBaseMapper;
import com.mannschaft.app.knowledgebase.dto.CreateKbPageRequest;
import com.mannschaft.app.knowledgebase.dto.KbPageResponse;
import com.mannschaft.app.knowledgebase.dto.KbPageSummaryResponse;
import com.mannschaft.app.knowledgebase.dto.MoveKbPageRequest;
import com.mannschaft.app.knowledgebase.dto.UpdateKbPageRequest;
import com.mannschaft.app.knowledgebase.service.KbPageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ナレッジベースページコントローラー。
 * ページのCRUD・ツリー操作・公開/アーカイブ・ピン留めAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class KbPageController {

    private static final String SCOPE_TYPE = "TEAM";

    private final KbPageService kbPageService;
    private final KnowledgeBaseMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * ページツリーを取得する。
     *
     * @param teamId チームID
     * @return ページサマリー一覧
     */
    @GetMapping("/teams/{teamId}/knowledge-base/pages")
    public ApiResponse<List<KbPageSummaryResponse>> getPageTree(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);
        String userRole = accessControlService.getRoleName(userId, teamId, SCOPE_TYPE);

        List<KbPageSummaryResponse> result = kbPageService
                .getPageTree(SCOPE_TYPE, teamId, userId, userRole)
                .getData()
                .stream()
                .map(mapper::toSummaryResponse)
                .toList();
        return ApiResponse.of(result);
    }

    /**
     * ページ詳細を取得する。
     *
     * @param teamId チームID
     * @param pageId ページID
     * @return ページ詳細
     */
    @GetMapping("/teams/{teamId}/knowledge-base/pages/{pageId}")
    public ApiResponse<KbPageResponse> getPage(@PathVariable Long teamId,
                                                @PathVariable Long pageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);
        String userRole = accessControlService.getRoleName(userId, teamId, SCOPE_TYPE);

        return ApiResponse.of(
                mapper.toResponse(kbPageService.getPage(pageId, SCOPE_TYPE, teamId, userId, userRole).getData())
        );
    }

    /**
     * ページを作成する。
     *
     * @param teamId  チームID
     * @param request 作成リクエスト
     * @return 作成されたページ
     */
    @PostMapping("/teams/{teamId}/knowledge-base/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KbPageResponse> createPage(@PathVariable Long teamId,
                                                   @Valid @RequestBody CreateKbPageRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        KbPageService.CreateKbPageRequest serviceReq = new KbPageService.CreateKbPageRequest(
                request.title(),
                request.slug(),
                request.body(),
                request.icon(),
                request.accessLevel(),
                request.parentId(),
                request.templateId()
        );

        return ApiResponse.of(
                mapper.toResponse(kbPageService.createPage(SCOPE_TYPE, teamId, userId, serviceReq).getData())
        );
    }

    /**
     * ページを更新する。
     *
     * @param teamId  チームID
     * @param pageId  ページID
     * @param request 更新リクエスト
     * @return 更新されたページ
     */
    @PatchMapping("/teams/{teamId}/knowledge-base/pages/{pageId}")
    public ApiResponse<KbPageResponse> updatePage(@PathVariable Long teamId,
                                                   @PathVariable Long pageId,
                                                   @Valid @RequestBody UpdateKbPageRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);
        String userRole = accessControlService.getRoleName(userId, teamId, SCOPE_TYPE);

        KbPageService.UpdateKbPageRequest serviceReq = new KbPageService.UpdateKbPageRequest(
                request.title(),
                request.body(),
                request.icon(),
                request.accessLevel(),
                request.version()
        );

        return ApiResponse.of(
                mapper.toResponse(
                        kbPageService.updatePage(pageId, SCOPE_TYPE, teamId, userId, userRole, serviceReq).getData()
                )
        );
    }

    /**
     * ページを削除する（論理削除）。
     *
     * @param teamId チームID
     * @param pageId ページID
     */
    @DeleteMapping("/teams/{teamId}/knowledge-base/pages/{pageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePage(@PathVariable Long teamId,
                           @PathVariable Long pageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        kbPageService.deletePage(pageId, SCOPE_TYPE, teamId);
    }

    /**
     * ページを移動する。
     *
     * @param teamId  チームID
     * @param pageId  ページID
     * @param request 移動リクエスト
     */
    @PatchMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void movePage(@PathVariable Long teamId,
                         @PathVariable Long pageId,
                         @RequestBody MoveKbPageRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        kbPageService.movePage(pageId, request.newParentId(), SCOPE_TYPE, teamId);
    }

    /**
     * ページを公開する。
     *
     * @param teamId チームID
     * @param pageId ページID
     * @return 更新されたページ
     */
    @PatchMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/publish")
    public ApiResponse<KbPageResponse> publishPage(@PathVariable Long teamId,
                                                    @PathVariable Long pageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        return ApiResponse.of(
                mapper.toResponse(kbPageService.publishPage(pageId, SCOPE_TYPE, teamId).getData())
        );
    }

    /**
     * ページをアーカイブする。
     *
     * @param teamId チームID
     * @param pageId ページID
     * @return 更新されたページ
     */
    @PatchMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/archive")
    public ApiResponse<KbPageResponse> archivePage(@PathVariable Long teamId,
                                                    @PathVariable Long pageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        return ApiResponse.of(
                mapper.toResponse(kbPageService.archivePage(pageId, SCOPE_TYPE, teamId).getData())
        );
    }

    /**
     * 最近更新されたページ一覧を取得する。
     *
     * @param teamId チームID
     * @return ページサマリー一覧（最大20件、更新日時降順）
     */
    @GetMapping("/teams/{teamId}/knowledge-base/recent")
    public ApiResponse<List<KbPageSummaryResponse>> getRecentPages(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);
        String userRole = accessControlService.getRoleName(userId, teamId, SCOPE_TYPE);

        List<KbPageSummaryResponse> result = kbPageService
                .getRecentPages(SCOPE_TYPE, teamId, userRole)
                .getData()
                .stream()
                .map(mapper::toSummaryResponse)
                .toList();
        return ApiResponse.of(result);
    }

    /**
     * ピン留めページ一覧を取得する。
     *
     * @param teamId チームID
     * @return ピン留めページのサマリー一覧
     */
    @GetMapping("/teams/{teamId}/knowledge-base/pins")
    public ApiResponse<List<KbPageSummaryResponse>> getPinnedPages(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        List<KbPageSummaryResponse> result = kbPageService
                .getPinnedPages(SCOPE_TYPE, teamId)
                .getData()
                .stream()
                .map(mapper::toSummaryResponse)
                .toList();
        return ApiResponse.of(result);
    }

    /**
     * ページをピン留めする（ADMIN/DEPUTY_ADMIN のみ）。
     *
     * @param teamId チームID
     * @param pageId ページID
     * @return 空レスポンス
     */
    @PostMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/pin")
    public ApiResponse<Void> pinPage(@PathVariable Long teamId,
                                     @PathVariable Long pageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, SCOPE_TYPE);

        kbPageService.pinPage(pageId, SCOPE_TYPE, teamId, userId);
        return ApiResponse.of(null);
    }

    /**
     * ページのピン留めを解除する（ADMIN/DEPUTY_ADMIN のみ）。
     *
     * @param teamId チームID
     * @param pageId ページID
     */
    @DeleteMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpinPage(@PathVariable Long teamId,
                          @PathVariable Long pageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, SCOPE_TYPE);

        kbPageService.unpinPage(pageId, SCOPE_TYPE, teamId);
    }
}
