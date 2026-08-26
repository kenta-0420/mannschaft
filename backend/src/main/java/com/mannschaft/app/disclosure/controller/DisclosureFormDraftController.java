package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.disclosure.DraftStatus;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftResponse;
import com.mannschaft.app.disclosure.service.DisclosureFormDraftService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 重要事項説明書 ドラフト コントローラ（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 ドラフト API に対応。組織スコープのみ提供（{@code /api/v1/organizations/{id}/disclosure-drafts}）。</p>
 *
 * <p><strong>権限制御</strong>（認可根治戦役 Wave3-B4 で実装）: 閲覧系（一覧・詳細）は
 * {@code AccessControlService.checkMembership}、変更系（作成・更新・自動引用更新・削除）は
 * {@code checkAdminOrAbove} を {@link DisclosureFormDraftService} 側で検証する。
 * DEPUTY_ADMIN の Permission 単位（DISCLOSURE_VIEW）細分化は引き続き Phase 2-β-5 以降の課題として残す。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/disclosure-drafts")
@RequiredArgsConstructor
public class DisclosureFormDraftController {

    private final DisclosureFormDraftService draftService;

    @GetMapping
    public PagedResponse<DisclosureFormDraftResponse> list(
            @PathVariable("organizationId") Long organizationId,
            @RequestParam(value = "status", required = false) DraftStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(page, size);
        Page<DisclosureFormDraftResponse> result = draftService.list(organizationId, userId, status, pageable);
        return PagedResponse.of(
                result.getContent(),
                new PagedResponse.PageMeta(
                        result.getTotalElements(),
                        result.getNumber(),
                        result.getSize(),
                        result.getTotalPages()));
    }

    @GetMapping("/{draftId}")
    public ApiResponse<DisclosureFormDraftResponse> get(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("draftId") Long draftId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(draftService.get(organizationId, userId, draftId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DisclosureFormDraftResponse>> create(
            @PathVariable("organizationId") Long organizationId,
            @Valid @RequestBody DisclosureFormDraftRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        DisclosureFormDraftResponse created = draftService.create(organizationId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }

    @PutMapping("/{draftId}")
    public ApiResponse<DisclosureFormDraftResponse> update(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("draftId") Long draftId,
            @Valid @RequestBody DisclosureFormDraftRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(draftService.update(organizationId, draftId, userId, request));
    }

    @PostMapping("/{draftId}/refresh-auto-fill")
    public ApiResponse<DisclosureFormDraftResponse> refreshAutoFill(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("draftId") Long draftId,
            @RequestParam(value = "allowPersonalInfo", defaultValue = "false") boolean allowPersonalInfo) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(draftService.refreshAutoFill(
                organizationId, draftId, userId, allowPersonalInfo));
    }

    @DeleteMapping("/{draftId}")
    public ResponseEntity<Void> delete(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("draftId") Long draftId) {
        Long userId = SecurityUtils.getCurrentUserId();
        draftService.delete(organizationId, userId, draftId);
        return ResponseEntity.noContent().build();
    }
}
