package com.mannschaft.app.knowledgebase.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.knowledgebase.KnowledgeBaseMapper;
import com.mannschaft.app.knowledgebase.dto.CreateKbTemplateRequest;
import com.mannschaft.app.knowledgebase.dto.KbTemplateResponse;
import com.mannschaft.app.knowledgebase.dto.UpdateKbTemplateRequest;
import com.mannschaft.app.knowledgebase.service.KbTemplateService;
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
 * ナレッジベーステンプレートコントローラー。
 * テンプレートの一覧取得・作成・更新・削除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class KbTemplateController {

    private static final String SCOPE_TYPE = "TEAM";

    private final KbTemplateService kbTemplateService;
    private final KnowledgeBaseMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * テンプレート一覧を取得する。
     * システムテンプレート + チームテンプレートを返す。
     *
     * @param teamId チームID
     * @return テンプレート一覧
     */
    @GetMapping("/teams/{teamId}/knowledge-base/templates")
    public ApiResponse<List<KbTemplateResponse>> getTemplates(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        List<KbTemplateResponse> result = kbTemplateService
                .getTemplates(SCOPE_TYPE, teamId)
                .getData()
                .stream()
                .map(mapper::toTemplateResponse)
                .toList();
        return ApiResponse.of(result);
    }

    /**
     * テンプレートを作成する。
     *
     * @param teamId  チームID
     * @param request 作成リクエスト
     * @return 作成されたテンプレート
     */
    @PostMapping("/teams/{teamId}/knowledge-base/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KbTemplateResponse> createTemplate(@PathVariable Long teamId,
                                                           @Valid @RequestBody CreateKbTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        KbTemplateService.CreateKbTemplateRequest serviceReq = new KbTemplateService.CreateKbTemplateRequest(
                request.name(),
                request.body(),
                request.icon()
        );

        return ApiResponse.of(
                mapper.toTemplateResponse(
                        kbTemplateService.createTemplate(SCOPE_TYPE, teamId, userId, serviceReq).getData()
                )
        );
    }

    /**
     * テンプレートを更新する。
     *
     * @param teamId     チームID
     * @param templateId テンプレートID
     * @param request    更新リクエスト
     * @return 更新されたテンプレート
     */
    @PatchMapping("/teams/{teamId}/knowledge-base/templates/{templateId}")
    public ApiResponse<KbTemplateResponse> updateTemplate(@PathVariable Long teamId,
                                                           @PathVariable Long templateId,
                                                           @Valid @RequestBody UpdateKbTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        KbTemplateService.UpdateKbTemplateRequest serviceReq = new KbTemplateService.UpdateKbTemplateRequest(
                request.name(),
                request.body(),
                request.icon()
        );

        return ApiResponse.of(
                mapper.toTemplateResponse(
                        kbTemplateService.updateTemplate(templateId, SCOPE_TYPE, teamId, serviceReq, request.version())
                                .getData()
                )
        );
    }

    /**
     * テンプレートを削除する（論理削除）。
     *
     * @param teamId     チームID
     * @param templateId テンプレートID
     */
    @DeleteMapping("/teams/{teamId}/knowledge-base/templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@PathVariable Long teamId,
                               @PathVariable Long templateId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE);

        kbTemplateService.deleteTemplate(templateId, SCOPE_TYPE, teamId);
    }
}
