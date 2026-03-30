package com.mannschaft.app.knowledgebase.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.knowledgebase.KnowledgeBaseMapper;
import com.mannschaft.app.knowledgebase.dto.KbPageRevisionResponse;
import com.mannschaft.app.knowledgebase.dto.KbPageRevisionSummaryResponse;
import com.mannschaft.app.knowledgebase.dto.KbPageResponse;
import com.mannschaft.app.knowledgebase.service.KbRevisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ナレッジベースページリビジョンコントローラー。
 * リビジョン一覧取得・詳細取得・復元APIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class KbRevisionController {

    private static final String SCOPE_TYPE = "TEAM";

    private final KbRevisionService kbRevisionService;
    private final KnowledgeBaseMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * ページのリビジョン一覧を取得する。
     *
     * @param teamId チームID
     * @param pageId ページID
     * @return リビジョンサマリー一覧
     */
    @GetMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/revisions")
    public ApiResponse<List<KbPageRevisionSummaryResponse>> getRevisions(@PathVariable Long teamId,
                                                                          @PathVariable Long pageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);
        String userRole = accessControlService.getRoleName(userId, teamId, SCOPE_TYPE);

        List<KbPageRevisionSummaryResponse> result = kbRevisionService
                .getRevisions(pageId, userId, userRole)
                .getData()
                .stream()
                .map(mapper::toRevisionSummary)
                .toList();
        return ApiResponse.of(result);
    }

    /**
     * リビジョン詳細を取得する。
     *
     * @param teamId     チームID
     * @param pageId     ページID
     * @param revisionId リビジョンID
     * @return リビジョン詳細
     */
    @GetMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/revisions/{revisionId}")
    public ApiResponse<KbPageRevisionResponse> getRevision(@PathVariable Long teamId,
                                                            @PathVariable Long pageId,
                                                            @PathVariable Long revisionId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);
        String userRole = accessControlService.getRoleName(userId, teamId, SCOPE_TYPE);

        return ApiResponse.of(
                mapper.toRevisionResponse(
                        kbRevisionService.getRevision(pageId, revisionId, userId, userRole).getData()
                )
        );
    }

    /**
     * リビジョンを復元する。
     *
     * @param teamId     チームID
     * @param pageId     ページID
     * @param revisionId 復元するリビジョンID
     * @return 復元後のページ
     */
    @PostMapping("/teams/{teamId}/knowledge-base/pages/{pageId}/revisions/{revisionId}/restore")
    public ApiResponse<KbPageResponse> restoreRevision(@PathVariable Long teamId,
                                                        @PathVariable Long pageId,
                                                        @PathVariable Long revisionId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);
        String userRole = accessControlService.getRoleName(userId, teamId, SCOPE_TYPE);

        return ApiResponse.of(
                mapper.toResponse(
                        kbRevisionService.restoreRevision(pageId, revisionId, userId, userRole).getData()
                )
        );
    }
}
