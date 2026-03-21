package com.mannschaft.app.queue.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.dto.CategoryResponse;
import com.mannschaft.app.queue.dto.CreateCategoryRequest;
import com.mannschaft.app.queue.dto.UpdateCategoryRequest;
import com.mannschaft.app.queue.service.QueueCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 順番待ちカテゴリコントローラー。カテゴリのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/queue/categories")
@Tag(name = "順番待ちカテゴリ管理", description = "F03.7 順番待ちカテゴリのCRUD")
@RequiredArgsConstructor
public class QueueCategoryController {

    private final QueueCategoryService categoryService;

    /**
     * カテゴリ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "カテゴリ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> listCategories(
            @PathVariable Long teamId) {
        List<CategoryResponse> categories = categoryService.listCategories(
                QueueScopeType.TEAM, teamId);
        return ResponseEntity.ok(ApiResponse.of(categories));
    }

    /**
     * カテゴリ詳細を取得する。
     */
    @GetMapping("/{categoryId}")
    @Operation(summary = "カテゴリ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategory(
            @PathVariable Long teamId,
            @PathVariable Long categoryId) {
        CategoryResponse category = categoryService.getCategory(
                categoryId, QueueScopeType.TEAM, teamId);
        return ResponseEntity.ok(ApiResponse.of(category));
    }

    /**
     * カテゴリを作成する。
     */
    @PostMapping
    @Operation(summary = "カテゴリ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse category = categoryService.createCategory(
                request, QueueScopeType.TEAM, teamId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(category));
    }

    /**
     * カテゴリを更新する。
     */
    @PatchMapping("/{categoryId}")
    @Operation(summary = "カテゴリ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable Long teamId,
            @PathVariable Long categoryId,
            @Valid @RequestBody UpdateCategoryRequest request) {
        CategoryResponse category = categoryService.updateCategory(
                categoryId, request, QueueScopeType.TEAM, teamId);
        return ResponseEntity.ok(ApiResponse.of(category));
    }
}
