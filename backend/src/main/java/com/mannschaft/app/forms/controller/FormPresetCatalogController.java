package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.ApiResponse;
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
 * <p>本コントローラは認証済みユーザーであれば誰でも参照可能（カタログは公開情報扱い）。
 * SYSTEM_ADMIN 用の CRUD は別途 {@link FormPresetController} で提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/form-presets")
@Tag(name = "フォームプリセットカタログ", description = "F05.7 ADMIN 向けプリセットカタログ参照")
@RequiredArgsConstructor
public class FormPresetCatalogController {

    private final FormPresetService presetService;

    /**
     * スコープ視点でのプリセット一覧を取得する。
     *
     * <p>{@code scopeType} / {@code scopeId} は将来のスコープ別カスタムプリセット対応用に
     * URL に含めるが、Phase 11 第四陣 4-B 時点では使用しない（system_form_presets は全テナント共通）。</p>
     */
    @GetMapping
    @Operation(summary = "プリセットカタログ取得", description = "業界別フォームプリセットを返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FormPresetResponse>>> listCatalog(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(required = false) String category) {
        List<FormPresetResponse> presets = presetService.listPresets(category);
        return ResponseEntity.ok(ApiResponse.of(presets));
    }
}
