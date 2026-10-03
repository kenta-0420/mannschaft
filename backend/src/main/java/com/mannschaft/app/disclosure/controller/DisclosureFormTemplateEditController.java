package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.disclosure.dto.DisclosureCustomTemplateRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormTemplateResponse;
import com.mannschaft.app.disclosure.service.DisclosureFormTemplateEditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 重要事項説明書 カスタム様式テンプレート 編集系コントローラ（F09.14 Phase 3-C）。
 *
 * <p>設計書 §4 様式テンプレート API のうち POST / PUT / DELETE を提供する。
 * GET 系（一覧 / 詳細）は {@link DisclosureFormTemplateController} に分離されている。</p>
 *
 * <p>ベース URL: {@code /api/v1/organizations/{organizationId}/disclosure-templates}。
 * GET 系（{@code /api/v1/disclosure-templates}）とパスが異なる点に注意（設計書 §4 に準拠）。</p>
 *
 * <p><strong>権限制御</strong>（認可根治戦役 Wave3-B4 で実装）: {@code AccessControlService.checkAdminOrAbove}
 * を {@link DisclosureFormTemplateEditService} 側で検証する（当該組織の ADMIN/DEPUTY_ADMIN のみ変更可）。
 * DEPUTY_ADMIN の Permission 単位細分化は引き続き Phase 2-β-5 以降の課題として残す。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/disclosure-templates")
@RequiredArgsConstructor
public class DisclosureFormTemplateEditController {

    private final DisclosureFormTemplateEditService editService;

    /**
     * カスタム様式を新規作成する（設計書 §4: POST）。
     *
     * <p>1 組織あたり 10 件まで（{@code DisclosureFormTemplateEditService#MAX_CUSTOM_TEMPLATES_PER_ORG}）。
     * 超過時は {@code DISCLOSURE_013}。</p>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DisclosureFormTemplateResponse>> create(
            @PathVariable("organizationId") Long organizationId,
            @Valid @RequestBody DisclosureCustomTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        DisclosureFormTemplateResponse created = editService.createCustomTemplate(
                organizationId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }

    /**
     * カスタム様式を更新する（設計書 §4: PUT）。
     *
     * <p>楽観的ロック用に {@code versionLock} 必須。{@code code} は不変。
     * 既存ドラフトは {@code template_version_snapshot} で保護されるため、本更新により
     * 新しい {@code version} 文字列を設定しても既存ドラフトは壊れない。</p>
     */
    @PutMapping("/{templateId}")
    public ApiResponse<DisclosureFormTemplateResponse> update(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("templateId") Long templateId,
            @Valid @RequestBody DisclosureCustomTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(editService.updateCustomTemplate(organizationId, templateId, userId, request));
    }

    /**
     * カスタム様式を論理削除する（設計書 §4: DELETE）。
     *
     * <p>システム提供（{@code is_system_template=true}）テンプレートは削除不可（{@code DISCLOSURE_014}）。
     * 削除済テンプレを参照している既存ドラフトは {@code template_id} の RESTRICT 制約により
     * 削除できない（参照中のドラフトを先に処理する必要がある）が、本フェーズでは
     * 論理削除のため整合性は保たれる。</p>
     */
    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> delete(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("templateId") Long templateId) {
        Long userId = SecurityUtils.getCurrentUserId();
        editService.deleteCustomTemplate(organizationId, userId, templateId);
        return ResponseEntity.noContent().build();
    }
}
