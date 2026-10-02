package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.forms.FormScopes;
import com.mannschaft.app.forms.dto.FormPresetResponse;
import com.mannschaft.app.forms.service.FormPresetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * フォームプリセットカタログコントローラ（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>{@code GET /api/v1/{scopeType}/{scopeId}/form-presets}。
 * ADMIN 用に「業界別フォームプリセット（学校・PTA・自治会 等）」のカタログを返す。
 * 内部実装は {@code system_form_presets} テーブル（既存）の {@code is_active = true} 行を
 * カテゴリで絞り込んで返す。</p>
 *
 * <p>本コントローラは当該スコープのメンバーのみ参照可能（認可根治戦役 Wave3-B4 で
 * {@code AccessControlService.checkMembership} を実装。カタログ自体は全テナント共通データだが、
 * 無所属ユーザーによる任意 scopeId アクセスは弾く）。
 * SYSTEM_ADMIN 用の CRUD は別途 {@link FormPresetController} で提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/form-presets")
@Tag(name = "フォームプリセットカタログ", description = "F05.7 ADMIN 向けプリセットカタログ参照")
@RequiredArgsConstructor
public class FormPresetCatalogController {

    private final FormPresetService presetService;
    private final AccessControlService accessControlService;

    /**
     * スコープ視点でのプリセット一覧を取得する。
     *
     * <p>{@code scopeType} / {@code scopeId} は将来のスコープ別カスタムプリセット対応用に
     * URL に含めるが、Phase 11 第四陣 4-B 時点では使用しない（system_form_presets は全テナント共通）。</p>
     *
     * <p>認可根治戦役 Wave3-B4: カタログ自体は全テナント共通データで漏洩リスクは無いが、
     * URL に含まれる scopeId が実在のスコープであり呼び出し元がそのメンバーであることは
     * 最低限検証する（無所属の任意ユーザーが任意 scopeId を叩ける全体無防備を解消）。</p>
     */
    @GetMapping
    @Operation(summary = "プリセットカタログ取得", description = "業界別フォームプリセットを返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FormPresetResponse>>> listCatalog(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(required = false) String category) {
        accessControlService.checkMembership(
                SecurityUtils.getCurrentUserId(), scopeId, FormScopes.canonical(scopeType));
        List<FormPresetResponse> presets = presetService.listPresets(category);
        return ResponseEntity.ok(ApiResponse.of(presets));
    }
}
