package com.mannschaft.app.knowledgebase.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.knowledgebase.KnowledgeBaseMapper;
import com.mannschaft.app.knowledgebase.dto.KbPageSummaryResponse;
import com.mannschaft.app.knowledgebase.service.KbFavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ナレッジベースお気に入りコントローラー。
 * お気に入りの一覧取得・追加・削除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class KbFavoriteController {

    private static final String SCOPE_TYPE = "TEAM";

    private final KbFavoriteService kbFavoriteService;
    private final KnowledgeBaseMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * お気に入りページ一覧を取得する。
     *
     * @param teamId チームID
     * @return お気に入りページのサマリー一覧
     */
    @GetMapping("/teams/{teamId}/knowledge-base/favorites")
    public ApiResponse<List<KbPageSummaryResponse>> getFavorites(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        List<KbPageSummaryResponse> result = kbFavoriteService
                .getFavorites(userId, SCOPE_TYPE, teamId)
                .getData()
                .stream()
                .map(mapper::toSummaryResponse)
                .toList();
        return ApiResponse.of(result);
    }

    /**
     * ページをお気に入りに追加する。
     *
     * @param teamId チームID
     * @param pageId ページID
     * @return 空レスポンス
     */
    @PostMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/favorite")
    public ApiResponse<Void> addFavorite(@PathVariable Long teamId,
                                          @PathVariable Long pageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        kbFavoriteService.addFavorite(pageId, userId);
        return ApiResponse.of(null);
    }

    /**
     * ページをお気に入りから削除する。
     *
     * @param teamId チームID
     * @param pageId ページID
     */
    @DeleteMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFavorite(@PathVariable Long teamId,
                               @PathVariable Long pageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        kbFavoriteService.removeFavorite(pageId, userId);
    }
}
