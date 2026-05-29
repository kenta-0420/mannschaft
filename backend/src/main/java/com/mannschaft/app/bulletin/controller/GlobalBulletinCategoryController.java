package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.service.BulletinCategoryService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 掲示板カテゴリ「グローバル方式」コントローラー（F17.1 村掲示板グローバル方式 §3.12.1）。
 *
 * <p>パス変数方式（{@code /api/v1/{scopeType}/{scopeId}/bulletin/categories}）とは別に、
 * クエリパラメータでスコープを指定する経路を提供する。FE は村ページから
 * {@code scope_type=VILLAGE&scope_id=0&scope_village_id=<UUID>} の形で叩く。</p>
 *
 * <p>本コントローラーは <b>読取（GET 一覧）専用</b>。カテゴリの作成・更新・削除は後続足軽が
 * 同 prefix に追加する。第 2 セグメントがリテラル {@code bulletin} 固定のパス変数方式とは
 * URL が衝突しない（こちらは {@code /api/v1/bulletin/categories} 固定）。</p>
 *
 * <h2>スコープ分岐</h2>
 * <ul>
 *   <li>{@code VILLAGE}: {@code scope_village_id} 必須。村可視性認可 → 村カテゴリ一覧
 *       （{@link BulletinCategoryService#listVillageCategories}）</li>
 *   <li>{@code ORGANIZATION / TEAM / PERSONAL}: {@code scope_id} で既存経路
 *       （{@link BulletinCategoryService#listCategories}）へ委譲</li>
 * </ul>
 *
 * <p>不正な {@code scope_type} 値、VILLAGE での {@code scope_village_id} 欠落は
 * {@link CommonErrorCode#COMMON_001}（400）として弾く（500 を撒かない）。</p>
 */
@RestController
@RequestMapping("/api/v1/bulletin/categories")
@Tag(name = "掲示板カテゴリ（グローバル）", description = "F17.1 村掲示板グローバル方式 カテゴリ一覧")
@RequiredArgsConstructor
public class GlobalBulletinCategoryController {

    private final BulletinCategoryService categoryService;

    /**
     * カテゴリ一覧を取得する（グローバル方式）。
     *
     * @param scopeType      スコープ種別（{@code VILLAGE / ORGANIZATION / TEAM / PERSONAL}）
     * @param scopeId        スコープ ID（VILLAGE 時は 0）
     * @param scopeVillageId 村 ID（VILLAGE 時必須・それ以外は無視）
     * @return カテゴリ一覧（{@code { data: [...] }}）
     */
    @GetMapping
    @Operation(summary = "カテゴリ一覧（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> listCategories(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @RequestParam(value = "scope_village_id", required = false) UUID scopeVillageId) {
        ScopeType type = parseScopeType(scopeType);
        Long currentUserId = SecurityUtils.getCurrentUserId();

        List<CategoryResponse> categories;
        if (type == ScopeType.VILLAGE) {
            if (scopeVillageId == null) {
                throw new BusinessException(CommonErrorCode.COMMON_001);
            }
            categories = categoryService.listVillageCategories(scopeVillageId, currentUserId);
        } else {
            categories = categoryService.listCategories(type, scopeId, currentUserId);
        }
        return ResponseEntity.ok(ApiResponse.of(categories));
    }

    /**
     * scope_type をパースする。不正値は {@link CommonErrorCode#COMMON_001}（400）に変換する
     * （{@link ScopeType#fromPathSegment} の {@link IllegalArgumentException} を 500 にしない）。
     */
    private ScopeType parseScopeType(String scopeType) {
        try {
            return ScopeType.fromPathSegment(scopeType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }
}
