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
 * 当該ユーザーの所属組織コンテキストは {@code organizationId} クエリで明示する。
 * 認証だけ通れば呼べるが、Service 層で当該組織のメンバーシップが検証される。</p>
 */
@RestController
@RequestMapping("/api/v1/disclosure-templates")
@RequiredArgsConstructor
public class DisclosureFormTemplateController {

    private final DisclosureFormTemplateService templateService;

    /**
     * 利用可能な様式一覧を取得する。
     *
     * <p>{@code organizationId} 指定時は当該組織のカスタム様式を含む。未指定時はシステム提供のみ。
     * FIXME: Phase 2-β-5 以降、ログインユーザーの所属組織から自動解決する仕組みを追加する。
     * 本フェーズでは ADMIN フロント側で明示指定する想定。</p>
     */
    @GetMapping
    public ApiResponse<List<DisclosureFormTemplateResponse>> listAvailable(
            @RequestParam(value = "prefectureCode", required = false) String prefectureCode,
            @RequestParam(value = "organizationId", required = false) Long organizationId) {
        // 認証ガード（未認証は SecurityUtils が COMMON_000 を投げる）
        SecurityUtils.getCurrentUserId();
        String scopeType = organizationId != null ? "ORGANIZATION" : null;
        return ApiResponse.of(templateService.listAvailable(scopeType, organizationId, prefectureCode));
    }

    /**
     * 様式テンプレート詳細を取得する。
     *
     * <p>カスタム様式の場合、 {@code organizationId} を明示することで Service 層が
     * クロステナント遮断を行う。指定なしの場合システム提供のみ閲覧可。</p>
     */
    @GetMapping("/{templateId}")
    public ApiResponse<DisclosureFormTemplateResponse> get(
            @PathVariable("templateId") Long templateId,
            @RequestParam(value = "organizationId", required = false) Long organizationId) {
        SecurityUtils.getCurrentUserId();
        String scopeType = organizationId != null ? "ORGANIZATION" : null;
        return ApiResponse.of(templateService.get(scopeType, organizationId, templateId));
    }
}
