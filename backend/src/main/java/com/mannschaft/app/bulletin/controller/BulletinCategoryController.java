package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.CreateCategoryRequest;
import com.mannschaft.app.bulletin.dto.UpdateCategoryRequest;
import com.mannschaft.app.bulletin.service.BulletinCategoryService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 掲示板カテゴリコントローラー。カテゴリのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/bulletin/categories")
@Tag(name = "掲示板カテゴリ", description = "F05.1 掲示板カテゴリCRUD")
@RequiredArgsConstructor
public class BulletinCategoryController {

    private final BulletinCategoryService categoryService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * カテゴリ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "カテゴリ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> listCategories(
            @PathVariable String scopeType,
            @PathVariable Long scopeId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        List<CategoryResponse> categories = categoryService.listCategories(type, scopeId);
        return ResponseEntity.ok(ApiResponse.of(categories));
    }

    /**
     * カテゴリ詳細を取得する。
     */
    @GetMapping("/{categoryId}")
    @Operation(summary = "カテゴリ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategory(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long categoryId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        CategoryResponse response = categoryService.getCategory(type, scopeId, categoryId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * カテゴリを作成する。
     */
    @PostMapping
    @Operation(summary = "カテゴリ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @Valid @RequestBody CreateCategoryRequest request) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        CategoryResponse response = categoryService.createCategory(type, scopeId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * カテゴリを更新する。
     */
    @PutMapping("/{categoryId}")
    @Operation(summary = "カテゴリ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long categoryId,
            @Valid @RequestBody UpdateCategoryRequest request) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        CategoryResponse response = categoryService.updateCategory(type, scopeId, categoryId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * カテゴリを削除する。
     */
    @DeleteMapping("/{categoryId}")
    @Operation(summary = "カテゴリ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long categoryId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        categoryService.deleteCategory(type, scopeId, categoryId);
        return ResponseEntity.noContent().build();
    }
}
