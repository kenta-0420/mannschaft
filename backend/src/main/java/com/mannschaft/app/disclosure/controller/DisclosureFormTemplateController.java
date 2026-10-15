package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.disclosure.dto.DisclosureFormTemplateResponse;
import com.mannschaft.app.disclosure.service.DisclosureFormTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 重要事項説明書 様式テンプレート コントローラ（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 様式テンプレート API のうち GET 系（一覧 / 詳細）を提供する。
 * カスタム様式の作成 / 更新 / 削除は Phase 3 で別途実装する。</p>
 *
 * <p>ベース URL: {@code /api/v1/disclosure-templates}（システム提供 + ユーザー組織のカスタム統合）。
 * 当該ユーザーの所属組織コンテキストは {@code organizationId} クエリで明示する（必須）。
 * 認証だけ通れば呼べるが、Service 層で当該組織のメンバーシップが検証される。</p>
 *
 * <p><strong>権限制御</strong>（認可根治戦役 Wave3-B4 で実装）: {@code AccessControlService.checkMembership}
 * を {@link DisclosureFormTemplateService} 側で検証する（当該組織のメンバーのみ閲覧可）。
 * DEPUTY_ADMIN の Permission 単位（DISCLOSURE_VIEW）細分化は引き続き Phase 2-β-5 以降の課題として残す。</p>
 */
@RestController
@RequestMapping("/api/v1/disclosure-templates")
@RequiredArgsConstructor
public class DisclosureFormTemplateController {

    private final DisclosureFormTemplateService templateService;

    /**
     * 利用可能な様式一覧を取得する。
     *
     * <p>{@code organizationId} は必須。当該組織のカスタム様式 + システム提供様式を返す。
     * クロステナント遮断のため必ず明示指定すること。</p>
     */
    @GetMapping
    public ApiResponse<List<DisclosureFormTemplateResponse>> listAvailable(
            @RequestParam(value = "prefectureCode", required = false) String prefectureCode,
            @RequestParam(value = "organizationId") Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(
                templateService.listAvailable("ORGANIZATION", organizationId, userId, prefectureCode));
    }

    /**
     * 様式テンプレート詳細を取得する。
     *
     * <p>{@code organizationId} は必須。Service 層でクロステナント遮断を行う。</p>
     */
    @GetMapping("/{templateId}")
    public ApiResponse<DisclosureFormTemplateResponse> get(
            @PathVariable("templateId") Long templateId,
            @RequestParam(value = "organizationId") Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(templateService.get("ORGANIZATION", organizationId, userId, templateId));
    }
}
