package com.mannschaft.app.faq.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.faq.ScopeType;
import com.mannschaft.app.faq.dto.FaqEditorResponse;
import com.mannschaft.app.faq.dto.SaveFaqRequest;
import com.mannschaft.app.faq.service.FaqAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * F21.1 §5.5: チーム / 組織の公開FAQ 管理 API。
 *
 * <p>編集画面用の取得（固定6問 + 自由質問）と一括 upsert（固定 UPSERT・自由差分適用）を提供する。
 * 公開（permitAll）の取得 API は別 Controller（足軽C 担当）で実装する。</p>
 *
 * <p><strong>認可:</strong> F19.1 {@code AdminPublicSettingsController} の方式を踏襲し、
 * {@code @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')")} を付与する。
 * 操作ユーザー ID は {@link SecurityUtils#getCurrentUserId()} で取得する。</p>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5.6</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "FAQ管理 (F21.1 §5.5)", description = "チーム / 組織の公開FAQ（固定6問 + 自由質問）の取得・一括更新")
public class AdminFaqController {

    private final FaqAdminService faqAdminService;

    /**
     * チームの FAQ 編集ペイロードを取得する（固定6問 + 自由質問）。
     */
    @GetMapping("/api/v1/admin/teams/{teamId}/faqs")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')")
    @Operation(
            summary = "チームFAQ取得（編集画面用）",
            description = "ADMIN または SYSTEM_ADMIN が、固定6問（未回答含む）+ 自由質問を編集画面向けに取得する（F21.1 §5.5）。")
    public ResponseEntity<FaqEditorResponse> getTeamFaqs(@PathVariable Long teamId) {
        return ResponseEntity.ok(faqAdminService.getEditorPayload(ScopeType.TEAM, teamId));
    }

    /**
     * チームの FAQ を一括 upsert する。
     */
    @PutMapping("/api/v1/admin/teams/{teamId}/faqs")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')")
    @Operation(
            summary = "チームFAQ一括更新",
            description = "ADMIN または SYSTEM_ADMIN が、固定質問の回答 UPSERT・自由質問の差分適用を一括で行う（F21.1 §5.5）。")
    public ResponseEntity<Void> saveTeamFaqs(
            @PathVariable Long teamId,
            @Valid @RequestBody SaveFaqRequest req) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        faqAdminService.save(ScopeType.TEAM, teamId, req, operatorId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 組織の FAQ 編集ペイロードを取得する（固定6問 + 自由質問）。
     */
    @GetMapping("/api/v1/admin/organizations/{orgId}/faqs")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')")
    @Operation(
            summary = "組織FAQ取得（編集画面用）",
            description = "ADMIN または SYSTEM_ADMIN が、固定6問（未回答含む）+ 自由質問を編集画面向けに取得する（F21.1 §5.5）。")
    public ResponseEntity<FaqEditorResponse> getOrganizationFaqs(@PathVariable Long orgId) {
        return ResponseEntity.ok(faqAdminService.getEditorPayload(ScopeType.ORGANIZATION, orgId));
    }

    /**
     * 組織の FAQ を一括 upsert する。
     */
    @PutMapping("/api/v1/admin/organizations/{orgId}/faqs")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')")
    @Operation(
            summary = "組織FAQ一括更新",
            description = "ADMIN または SYSTEM_ADMIN が、固定質問の回答 UPSERT・自由質問の差分適用を一括で行う（F21.1 §5.5）。")
    public ResponseEntity<Void> saveOrganizationFaqs(
            @PathVariable Long orgId,
            @Valid @RequestBody SaveFaqRequest req) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        faqAdminService.save(ScopeType.ORGANIZATION, orgId, req, operatorId);
        return ResponseEntity.noContent().build();
    }
}
