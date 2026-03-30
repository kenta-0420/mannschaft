package com.mannschaft.app.knowledgebase.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.knowledgebase.KnowledgeBaseMapper;
import com.mannschaft.app.knowledgebase.dto.KbPageSummaryResponse;
import com.mannschaft.app.knowledgebase.dto.KbUploadUrlRequest;
import com.mannschaft.app.knowledgebase.dto.KbUploadUrlResponse;
import com.mannschaft.app.knowledgebase.service.KbImageService;
import com.mannschaft.app.knowledgebase.service.KbSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ナレッジベース検索・画像アップロードコントローラー。
 * 全文検索APIおよび画像アップロードURL取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class KbSearchController {

    private static final String SCOPE_TYPE = "TEAM";

    private final KbSearchService kbSearchService;
    private final KbImageService kbImageService;
    private final KnowledgeBaseMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * ページを全文検索する。
     *
     * @param teamId  チームID
     * @param keyword 検索キーワード
     * @return 検索結果のページサマリー一覧
     */
    @GetMapping("/teams/{teamId}/knowledge-base/search")
    public ApiResponse<List<KbPageSummaryResponse>> search(@PathVariable Long teamId,
                                                            @RequestParam String keyword) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);
        String userRole = accessControlService.getRoleName(userId, teamId, SCOPE_TYPE);

        List<KbPageSummaryResponse> result = kbSearchService
                .search(keyword, SCOPE_TYPE, teamId, userRole)
                .getData()
                .stream()
                .map(mapper::toSummaryResponse)
                .toList();
        return ApiResponse.of(result);
    }

    /**
     * 画像アップロード用 Pre-signed URL を取得する。
     *
     * @param teamId  チームID
     * @param request アップロードURLリクエスト
     * @return アップロードURLレスポンス
     */
    @PostMapping("/teams/{teamId}/knowledge-base/upload-url")
    public ApiResponse<KbUploadUrlResponse> getUploadUrl(@PathVariable Long teamId,
                                                          @Valid @RequestBody KbUploadUrlRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        KbImageService.ImageUploadUrlResult result = kbImageService
                .generateUploadUrl(SCOPE_TYPE, teamId, userId, request.contentType(), request.fileSize())
                .getData();

        return ApiResponse.of(mapper.toUploadUrlResponse(result));
    }
}
